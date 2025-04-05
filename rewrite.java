///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//COMPILE_OPTIONS -Xlint:deprecation -Xlint:unchecked -proc:none

//REPOS mavencentral
//DEPS info.picocli:picocli:4.7.6
//DEPS ch.qos.logback:logback-classic:1.5.3
//DEPS org.fusesource.jansi:jansi:2.4.1
//DEPS org.apache.maven:maven-core:3.9.9

//DEPS org.openrewrite:rewrite-bom:8.49.0@pom
//DEPS org.openrewrite:rewrite-core
//DEPS org.openrewrite:rewrite-java
//DEPS org.openrewrite:rewrite-java-8
//DEPS org.openrewrite:rewrite-java-11
//DEPS org.openrewrite:rewrite-xml
//DEPS org.openrewrite:rewrite-maven
//DEPS org.openrewrite:rewrite-properties
//DEPS org.openrewrite:rewrite-toml
//DEPS org.openrewrite:rewrite-yaml

//SOURCES LoggingUtils.java

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.joining;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import java.util.regex.Pattern;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.model.Repository;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.LargeSourceSet;
import org.openrewrite.RecipeRun;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.Validated;
import org.openrewrite.config.Environment;
import org.openrewrite.config.OptionDescriptor;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.JavaParser;
import org.openrewrite.marker.Generated;
import org.openrewrite.maven.MavenExecutionContextView;
import org.openrewrite.maven.MavenParser;
import org.openrewrite.maven.MavenSettings;
import org.openrewrite.maven.internal.RawRepositories;
import org.openrewrite.maven.tree.ProfileActivation;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.toml.TomlParser;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.tree.Xml;
import org.openrewrite.yaml.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.fusesource.jansi.AnsiConsole;

@Command(name = "rewrite", mixinStandardHelpOptions = true, version = "rewrite 0.2", description = "rewrite made with jbang", subcommands = Rewrite.RewriteDiscover.class)
class Rewrite implements Callable<Integer> {

    private static final String INDENT_SPACES = "    ";

    // Singleton instance for static method access
    private static final Rewrite INSTANCE = new Rewrite();

    // SLF4J Logger
    public static final Logger logger = LoggerFactory.getLogger(Rewrite.class);

    public static Rewrite getInstance() {
        return INSTANCE;
    }

    private Rewrite() {
        // Private constructor for singleton
    }

    @Option(names = {"--baseDir",
            "--base-dir"}, description = "Base directory for the project. Defaults to current directory.")
    private String baseDirPath = ".";

    private Path baseDir() {
        return Path.of(baseDirPath).toAbsolutePath().normalize();
    }

    @Option(names = "--recipes", split = ",")
    Set<String> activeRecipes = emptySet();

    @Option(names = "--styles", split = ",")
    protected Set<String> activeStyles = Collections.emptySet();

    @Option(names = {"--javaSources", "--java-sources"}, defaultValue = ".", split = ",")
    List<String> javaSourcePaths = emptyList();

    @Option(names = "--discover-resources", defaultValue = "true", description = "Attempt to discover resource files (yml, xml, properties) in source directories.")
    boolean discoverResources;

    @Option(names = "--classpath", description = "Specify the classpath for type resolution, using the system path separator.", split = "${sys:path.separator}")
    List<String> classpathElements = emptyList();

    @Option(names = {"--failOnInvalidActiveRecipes", "--fail-on-invalid-recipes"}, defaultValue = "false")
    boolean failOnInvalidActiveRecipes;

    @Option(names = {"--reportOutputDirectory", "--report"}, defaultValue = "./rewrite")
    private File reportOutputDirectory;

    @Option(names = {"--failOnDryRunResults", "--fail-on-dry-run"}, defaultValue = "false")
    boolean failOnDryRunResults;

    @Option(names = "--dry-run", defaultValue = "false")
    boolean dryRun;

    // Add LogLevel enum
    public enum LogLevel {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    @Option(names = "--recipe-change-log-level", defaultValue = "WARN", description = "Log level for reporting recipe changes (DEBUG, INFO, WARN, ERROR).")
    LogLevel recipeChangeLogLevel = LogLevel.WARN;

    // Add a flag to disable ANSI colors
    @Option(names = {"--no-color"}, description = "Disable colorized output", defaultValue = "false")
    boolean noColor;

    public static void main(String... args) {
        // Initialize Jansi - required for ANSI colors in Windows
        AnsiConsole.systemInstall();

        try {
            // Configure the logging system
            LoggingUtils.configureLogbackProgrammatically();

            // Create instance and parse command line
            CommandLine cmd = new CommandLine(INSTANCE);
            
            // Set color scheme for help output
            cmd.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.AUTO));

            int exitCode = cmd.execute(args);
            System.exit(exitCode);
        } finally {
            // Restore terminal settings
            AnsiConsole.systemUninstall();
        }
    }

    Environment environment() {

        Environment.Builder env = Environment.builder().scanRuntimeClasspath().scanUserHome();

        return env.build();
    }

    protected ExecutionContext executionContext() {
        return new InMemoryExecutionContext(t ->
                logger.warn("Error during recipe execution: {}", t.getMessage(), t)
        );
    }

    private static RawRepositories buildRawRepositories(List<Repository> repositoriesToMap) {
        if (repositoriesToMap == null) {
            return null;
        }

        RawRepositories rawRepositories = new RawRepositories();
        List<RawRepositories.Repository> transformedRepositories = repositoriesToMap
                .stream().map(r -> new RawRepositories.Repository(
                r.getId(),
                r.getUrl(),
                r.getReleases() == null ? null
                        : new RawRepositories.ArtifactPolicy(Boolean.toString(r.getReleases().isEnabled())),
                r.getSnapshots() == null ? null
                        : new RawRepositories.ArtifactPolicy(Boolean.toString(r.getSnapshots().isEnabled()))))
                .toList();
        rawRepositories.setRepositories(transformedRepositories);
        return rawRepositories;
    }

    private MavenSettings buildSettings() {
        MavenExecutionRequest mer = new DefaultMavenExecutionRequest();

        String localRepo = determineLocalRepository(mer);
        MavenSettings.Profiles profiles = buildProfiles(mer);
        MavenSettings.ActiveProfiles activeProfiles = extractActiveProfiles(mer);
        MavenSettings.Mirrors mirrors = buildMirrors(mer);
        MavenSettings.Servers servers = createEmptyServers();

        return new MavenSettings(localRepo, profiles, activeProfiles, mirrors, servers);
    }

    private String determineLocalRepository(MavenExecutionRequest mer) {
        File localRepoPath = mer.getLocalRepositoryPath();
        return (localRepoPath != null)
                ? localRepoPath.toString()
                : Paths.get(System.getProperty("user.home"), ".m2", "repository").toString();
    }

    private MavenSettings.Profiles buildProfiles(MavenExecutionRequest mer) {
        MavenSettings.Profiles profiles = new MavenSettings.Profiles();
        profiles.setProfiles(
                mer.getProfiles().stream()
                        .map(this::convertProfile)
                        .toList());
        return profiles;
    }

    private MavenSettings.Profile convertProfile(org.apache.maven.model.Profile p) {
        ProfileActivation.Property activationProperty = extractActivationProperty(p);

        return new MavenSettings.Profile(
                p.getId(),
                createProfileActivation(p, activationProperty),
                buildRawRepositories(p.getRepositories()));
    }

    private ProfileActivation.Property extractActivationProperty(org.apache.maven.model.Profile p) {
        if (p.getActivation() != null && p.getActivation().getProperty() != null) {
            return new ProfileActivation.Property(
                    p.getActivation().getProperty().getName(),
                    p.getActivation().getProperty().getValue());
        }
        return null;
    }

    private ProfileActivation createProfileActivation(org.apache.maven.model.Profile p, ProfileActivation.Property property) {
        if (p.getActivation() == null) {
            return null;
        }

        return new ProfileActivation(
                p.getActivation().isActiveByDefault(),
                p.getActivation().getJdk(),
                property);
    }

    private MavenSettings.ActiveProfiles extractActiveProfiles(MavenExecutionRequest mer) {
        MavenSettings.ActiveProfiles activeProfiles = new MavenSettings.ActiveProfiles();
        List<String> merActiveProfiles = mer.getActiveProfiles();
        activeProfiles.setActiveProfiles(merActiveProfiles != null ? merActiveProfiles : Collections.emptyList());
        return activeProfiles;
    }

    private MavenSettings.Mirrors buildMirrors(MavenExecutionRequest mer) {
        MavenSettings.Mirrors mirrors = new MavenSettings.Mirrors();
        mirrors.setMirrors(
                mer.getMirrors().stream()
                        .map(this::convertMirror)
                        .toList());
        return mirrors;
    }

    private MavenSettings.Mirror convertMirror(org.apache.maven.settings.Mirror m) {
        return new MavenSettings.Mirror(
                m.getId(),
                m.getUrl(),
                m.getMirrorOf(),
                null,
                null);
    }

    private MavenSettings.Servers createEmptyServers() {
        MavenSettings.Servers servers = new MavenSettings.Servers();
        servers.setServers(emptyList());
        return servers;
    }

    public Xml.Document parseMaven(ExecutionContext ctx) {
        // Check if pom.xml exists
        Path pomPath = baseDir().resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            logger.info("No pom.xml found in base directory: {}", baseDir());
            return null;
        }

        List<Path> pomToParse = Collections.singletonList(pomPath);
        MavenParser.Builder parserBuilder = createMavenParserBuilder(ctx);
        List<SourceFile> parsedPoms = parseWithBuilder(parserBuilder, pomToParse, ctx);

        return extractXmlDocument(parsedPoms);
    }

    private MavenParser.Builder createMavenParserBuilder(ExecutionContext ctx) {
        MavenParser.Builder builder = MavenParser.builder();

        // Configure maven settings
        MavenSettings settings = buildSettings();
        MavenExecutionContextView mavenExecutionContext = MavenExecutionContextView.view(ctx);
        mavenExecutionContext.setMavenSettings(settings);

        // Add active profiles if available
        addActiveProfilesToBuilder(builder, settings);

        return builder;
    }

    private void addActiveProfilesToBuilder(MavenParser.Builder builder, MavenSettings settings) {
        if (settings.getActiveProfiles() == null) {
            return;
        }

        List<String> activeProfiles = settings.getActiveProfiles().getActiveProfiles();
        if (activeProfiles != null && !activeProfiles.isEmpty()) {
            builder.activeProfiles(activeProfiles.toArray(new String[0]));
        }
    }

    private List<SourceFile> parseWithBuilder(MavenParser.Builder builder, List<Path> paths, ExecutionContext ctx) {
        return builder.build()
                .parse(paths, baseDir(), ctx)
                .toList();
    }

    private Xml.Document extractXmlDocument(List<SourceFile> parsedFiles) {
        return parsedFiles.stream()
                .filter(Xml.Document.class::isInstance)
                .map(Xml.Document.class::cast)
                .findFirst()
                .orElse(null);
    }

    public static List<Path> listJavaSources(String sourceDirectory) {
        File sourceDirectoryFile = new File(sourceDirectory);
        if (!sourceDirectoryFile.exists()) {
            return emptyList();
        }

        Path sourceRoot = sourceDirectoryFile.toPath();
        try {
            // Use Files.walkFileTree like in plugin v5.43.1
            List<Path> result = new ArrayList<>();
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isDirectory() && file.toString().endsWith(".java")) {
                        try {
                            // Still normalize the path
                            result.add(file.toRealPath().normalize());
                        } catch (IOException e) {
                            // Handle exception during path normalization
                            logger.warn("Could not normalize path: {} - {}", file, e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    // Handle errors visiting files (e.g. permission issues)
                    logger.warn("Failed to visit file: {} - {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
            // Return distinct paths
            return result.stream().distinct().toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list Java source files in " + sourceDirectory, e);
        }
    }

    private static Set<Path> listResourceFiles(List<String> sourceDirectories) {
        Set<Path> resourceFiles = new HashSet<>();
        Set<String> resourceExtensions = Set.of(".yml", ".yaml", ".properties", ".xml", ".toml");

        for (String sourceDir : sourceDirectories) {
            addResourcesFromDirectory(resourceFiles, resourceExtensions, sourceDir);
        }
        return resourceFiles;
    }

    private static void addResourcesFromDirectory(Set<Path> resourceFiles, Set<String> resourceExtensions, String sourceDir) {
        File sourceDirectoryFile = new File(sourceDir);
        if (!isValidDirectory(sourceDirectoryFile)) {
            return;
        }

        Path sourceRoot = sourceDirectoryFile.toPath();
        try {
            findAndAddResources(resourceFiles, resourceExtensions, sourceRoot);
        } catch (IOException e) {
            logger.warn("Could not scan directory for resources: {} - {}", sourceRoot, e.getMessage());
        }
    }

    private static boolean isValidDirectory(File dir) {
        return dir.exists() && dir.isDirectory();
    }

    private static void findAndAddResources(Set<Path> resourceFiles, Set<String> resourceExtensions, Path sourceRoot)
            throws IOException {
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            walk.filter(p -> !Files.isDirectory(p))
                    .filter(p -> hasResourceExtension(p, resourceExtensions))
                    .map(Rewrite::normalizePathSafely)
                    .filter(Objects::nonNull)
                    .forEach(resourceFiles::add);
        }
    }

    private static boolean hasResourceExtension(Path path, Set<String> resourceExtensions) {
        String fileName = path.getFileName().toString();
        return resourceExtensions.stream().anyMatch(fileName::endsWith);
    }

    private static Path normalizePathSafely(Path path) {
        try {
            return path.toRealPath().normalize();
        } catch (IOException e) {
            logger.warn("Could not normalize path: {} - {}", path, e.getMessage());
            return null;
        }
    }

    public static class ResultsContainer {
        final Path projectRoot;
        final List<Result> generated = new ArrayList<>();
        final List<Result> deleted = new ArrayList<>();
        final List<Result> moved = new ArrayList<>();
        final List<Result> refactoredInPlace = new ArrayList<>();

        public ResultsContainer(Path projectRoot, Collection<Result> results) {
            this.projectRoot = projectRoot;

            if (results == null || results.isEmpty()) {
                return;
            }

            results.forEach(this::categorizeResult);
        }

        private void categorizeResult(Result result) {
            // Skip invalid results that have neither before nor after state
            if (result.getBefore() == null && result.getAfter() == null) {
                return;
            }

            // Generated new file
            if (result.getBefore() == null && result.getAfter() != null) {
                generated.add(result);
                return;
            }

            // Deleted file
            if (result.getBefore() != null && result.getAfter() == null) {
                deleted.add(result);
                return;
            }

            // Moved file (path changed)
            if (result.getBefore() != null && result.getAfter() != null
                    && !result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())) {
                moved.add(result);
                return;
            }

            // Refactored in place (content changed but path is the same)
            refactoredInPlace.add(result);
        }

        public Path getProjectRoot() {
            return projectRoot;
        }

        public boolean isNotEmpty() {
            return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
        }
    }

    // Extract method to handle recipe activation
    private org.openrewrite.Recipe activateRecipesWithFallback(Environment env) {
        var recipe = env.activateRecipes(activeRecipes);
        if (recipe.getRecipeList().isEmpty() || recipe.getName().equals("org.openrewrite.Recipe$Noop")) {
            // Fallback: try to find matching recipes from the descriptors
            var matchingRecipeDescriptors = env.listRecipeDescriptors()
                    .stream()
                    .filter(rd -> activeRecipes.stream().anyMatch(a -> rd.getName().equalsIgnoreCase(a)))
                    .toList();
            if (!matchingRecipeDescriptors.isEmpty()) {
                var names = matchingRecipeDescriptors.stream()
                        .map(RecipeDescriptor::getName)
                        .collect(java.util.stream.Collectors.toSet());
                logger.info("Activating recipes (fallback): {}", names);
                return env.activateRecipes(names);
            }
            logger.warn("No matching recipes found for specified active recipes: {}", activeRecipes);
            return recipe;
        }
        return recipe;
    }

    // Extract method to parse Java sources
    private List<SourceFile> parseJavaSources(List<Path> javaSources, List<Path> classpath, List<NamedStyles> styles, ExecutionContext ctx) {
        List<SourceFile> sourceFiles = new ArrayList<>();
        sourceFiles.addAll(
                JavaParser.fromJavaVersion()
                        .styles(styles)
                        .classpath(classpath)
                        .logCompilationWarningsAndErrors(true).build().parse(javaSources, baseDir(), ctx)
                        .toList());
        logger.info("{} java files parsed.", sourceFiles.size());
        return sourceFiles;
    }

    // Extract method to parse resource files of a specific type
    private void parseResourcesOfType(List<SourceFile> sourceFiles, Set<Path> resources, String type,
                                      java.util.function.Predicate<Path> filter,
                                      java.util.function.Function<List<Path>, Stream<SourceFile>> parser,
                                      ExecutionContext ctx) {
        logger.info("Parsing {} files...", type);
        List<Path> typePaths = resources.stream().filter(filter).toList();
        if (!typePaths.isEmpty()) {
            sourceFiles.addAll(parser.apply(typePaths).toList());
            logger.info("Parsed {} {} files.", typePaths.size(), type);
        } else {
            logger.info("No {} files found to parse.", type);
        }
    }

    // Main method to discover and apply recipes to source files
    Rewrite.ResultsContainer listResults() {
        // Setup environment and check for recipes
        var env = environment();
        if (activeRecipes.isEmpty()) {
            logger.warn("No recipes specified. Activate a recipe on the command line with '--recipes com.fully.qualified.RecipeClassName'");
            return new ResultsContainer(baseDir(), emptyList());
        }

        // Activate recipes and styles
        var recipe = activateRecipesWithFallback(env);
        if (recipe.getRecipeList().isEmpty() && activeRecipes.isEmpty()) {
            return new ResultsContainer(baseDir(), emptyList());
        }

        List<NamedStyles> styles = env.activateStyles(activeStyles);
        ExecutionContext ctx = executionContext();

        // Validate recipes
        validateRecipes(recipe, ctx);

        // Parse Java source files
        List<SourceFile> sourceFiles = parseAllJavaSourceFiles(styles, ctx);

        // Discover and parse resource files
        parseResourceFiles(sourceFiles, ctx);

        // Parse Maven POM if available
        parseMavenPom(sourceFiles, ctx);

        // Execute recipes and return results
        return executeRecipesAndGetResults(recipe, sourceFiles, ctx);
    }

    /**
     * Enhanced console output with prefixes, symbols and structured indentation
     */
    private void printColored(String message, String style) {
        LoggingUtils.printColored(message, style, noColor);
    }

    /**
     * Format message with structured indentation and consistent styling
     */
    private void printIndented(String message, String style, int indentLevel) {
        LoggingUtils.printIndented(message, style, indentLevel, noColor);
    }

    // log method to mimic plugin behavior with improved formatting
    protected void log(LogLevel logLevel, CharSequence content) {
        String style;

        if (logLevel == LogLevel.DEBUG) {
            style = "info";
            if (logger.isInfoEnabled()) {
                printColored(content.toString(), style);
            }
        } else if (logLevel == LogLevel.INFO) {
            style = "info";
            if (logger.isInfoEnabled()) {
                printColored(content.toString(), style);
            }
        } else if (logLevel == LogLevel.WARN) {
            style = "warning";
            if (logger.isWarnEnabled()) {
                printColored(content.toString(), style);
            }
        } else if (logLevel == LogLevel.ERROR) {
            style = "error";
            if (logger.isErrorEnabled()) {
                printColored(content.toString(), style);
            }
        }
    }

    // Updated Source URL
    // Source:
    // https://sourcegraph.com/github.com/openrewrite/rewrite-maven-plugin@v5.40.0/-/blob/src/main/java/org/openrewrite/maven/AbstractRewriteBaseRunMojo.java?L461-469
    protected void logRecipesThatMadeChanges(Result result) {
        int indentLevel = 2; // Start with 2-level indent
        // Print recipes that made changes with progressive indentation
        for (RecipeDescriptor recipeDescriptor : result.getRecipeDescriptorsThatMadeChanges()) {
            logRecipe(recipeDescriptor, indentLevel);
            indentLevel += 2; // Increase indent for child recipes
        }
    }

    // Updated Source URL
    // Source:
    // https://sourcegraph.com/github.com/openrewrite/rewrite-maven-plugin@v5.40.0/-/blob/src/main/java/org/openrewrite/maven/AbstractRewriteBaseRunMojo.java?L471-489
    private void logRecipe(RecipeDescriptor rd, int indentLevel) {
        printIndented(buildRecipeLogMessage(rd), LoggingUtils.STYLE_RECIPE, indentLevel);
        logChildRecipes(rd, indentLevel + 2);
    }

    // Extract recipe message building without prefix
    private String buildRecipeLogMessage(RecipeDescriptor rd) {
        String message = LoggingUtils.formatJavaName(rd.getName(), noColor);
        
        // Add recipe options if present
        String options = formatRecipeOptions(rd);
        if (!options.isEmpty()) {
            message += " [" + options + "]";
        }
        
        return message;
    }

    // Extract options formatting
    private String formatRecipeOptions(RecipeDescriptor rd) {
        if (rd.getOptions().isEmpty()) {
            return "";
        }

        return rd.getOptions().stream()
                .map(this::formatOption)
                .filter(Objects::nonNull)
                .collect(joining(", "));
    }

    // Extract single option formatting
    private String formatOption(OptionDescriptor option) {
        if (option.getValue() != null) {
            return option.getName() + "=" + option.getValue();
        }
        return null;
    }

    // Extract child recipe logging and use colors
    private void logChildRecipes(RecipeDescriptor rd, int indentLevel) {
        if (rd.getRecipeList().isEmpty()) {
            return;
        }

        String childPrefix = " ".repeat(indentLevel * 4);
        for (RecipeDescriptor childRecipe : rd.getRecipeList()) {
            String message = buildRecipeLogMessage(childRecipe);

            if (!noColor) {
                printIndented(message, LoggingUtils.STYLE_RECIPE, indentLevel);
            } else {
                log(recipeChangeLogLevel, message);
            }

            logChildRecipes(childRecipe, indentLevel + 2);
        }
    }

    // Colorize the generated file reports with improved semantics
    private void reportGeneratedFiles(ResultsContainer results) {
        for (Result result : results.generated) {
            if (result.getAfter() != null) {
                printColored("File Creation:", "heading");
                printIndented("Generate new file: " + result.getAfter().getSourcePath(), "success", 1);
                printIndented("Applied by:", "info", 1);
                logRecipesThatMadeChanges(result);
                System.out.println(); // Add spacing between entries
            }
        }
    }

    // Colorize the deleted file reports
    private void reportDeletedFiles(ResultsContainer results) {
        for (Result result : results.deleted) {
            if (result.getBefore() != null) {
                printColored("File Deletion:", "heading");
                printIndented("Delete file: " + result.getBefore().getSourcePath(), "error", 1);
                printIndented("Applied by:", "info", 1);
                logRecipesThatMadeChanges(result);
                System.out.println(); // Add spacing between entries
            }
        }
    }

    // Colorize the moved file reports
    private void reportMovedFiles(ResultsContainer results) {
        for (Result result : results.moved) {
            if (result.getBefore() != null && result.getAfter() != null) {
                printColored("File Move Operation:", "heading");
                printIndented("Move from: " + result.getBefore().getSourcePath(), "warning", 1);
                printIndented("Move to: " + result.getAfter().getSourcePath(), "success", 1);
                printIndented("Applied by:", "info", 1);
                logRecipesThatMadeChanges(result);
                System.out.println(); // Add spacing between entries
            }
        }
    }

    // Colorize the refactored file reports
    private void reportRefactoredFiles(ResultsContainer results) {
        for (Result result : results.refactoredInPlace) {
            if (result.getBefore() != null) {
                printColored("Code Refactoring:", "heading");
                printIndented("Modify file: " + result.getBefore().getSourcePath(), "warning", 1);
                printIndented("Applied by:", "info", 1);
                logRecipesThatMadeChanges(result);
                System.out.println(); // Add spacing between entries
            }
        }
    }

    // Extract method to create directory safely
    private void createDirectorySafely(File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            logger.warn("Failed to create directory: {}", directory);
        }
    }

    // Extract method to write patch file
    private void writePatchFile(ResultsContainer results) {
        // Create report directory if needed
        createDirectorySafely(reportOutputDirectory);

        Path patchFile = reportOutputDirectory.toPath().resolve("rewrite.patch");
        try (BufferedWriter writer = Files.newBufferedWriter(patchFile)) {
            // Combine all result streams and write diffs
            getAllResultsStream(results)
                    .map(Result::diff)
                    .forEach(diff -> writeLineToPatchFile(writer, diff));
        } catch (Exception e) {
            throw new RewriteExecutionException("Unable to generate rewrite result file", e);
        }

        System.out.println(); // Add spacing
        printColored("Report Generation:", "heading");
        printIndented("Report available at: " + patchFile.normalize(), "filename", 1);
    }

    // Helper method to get combined stream of all results
    private Stream<Result> getAllResultsStream(ResultsContainer results) {
        return Stream.concat(
                Stream.concat(results.generated.stream(), results.deleted.stream()),
                Stream.concat(results.moved.stream(), results.refactoredInPlace.stream())
        );
    }

    // Helper method to write a line to the patch file with exception handling
    private void writeLineToPatchFile(BufferedWriter writer, String line) {
        try {
            writer.write(line + "\n");
        } catch (IOException e) {
            throw new RewriteExecutionException("Failed to write diff", e);
        }
    }

    // Utility method to extract file content from SourceFile
    private String extractFileContent(SourceFile sourceFile) {
        Charset charset = sourceFile.getCharset();
        return new String(sourceFile.printAll().getBytes(charset), charset);
    }

    // Validate recipes with potential failure handling
    private void validateRecipes(org.openrewrite.Recipe recipe, ExecutionContext ctx) {
        logger.info("Validating active recipes...");
        @SuppressWarnings("rawtypes")
        Validated validated = recipe.validate(ctx);
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<Validated.Invalid> failedValidations = validated.failures();

        if (!failedValidations.isEmpty()) {
            failedValidations.forEach(failedValidation -> logger.error(
                    "Recipe validation error in " + failedValidation.getProperty() + ": "
                            + failedValidation.getMessage(),
                    failedValidation.getException()));

            if (failOnInvalidActiveRecipes) {
                throw new IllegalStateException(
                        "Recipe validation errors detected as part of one or more activeRecipe(s). Please check error logs.");
            } else {
                logger.error(
                        "Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless.");
            }
        }
    }

    // Parse all Java source files from configured paths
    private List<SourceFile> parseAllJavaSourceFiles(List<NamedStyles> styles, ExecutionContext ctx) {
        // Collect all Java sources from configured paths
        List<Path> javaSources = new ArrayList<>();
        javaSourcePaths.forEach(path -> javaSources.addAll(listJavaSources(path)));

        if (logger.isInfoEnabled()) {
            logger.info("Parsing Java files found in: {}", javaSourcePaths.stream().collect(joining(", ")));
        }

        // Prepare classpath for type resolution
        List<Path> classpath = prepareClasspath();

        // Parse Java sources with the prepared classpath
        return parseJavaSources(javaSources, classpath, styles, ctx);
    }

    // Prepare classpath for Java parsing
    private List<Path> prepareClasspath() {
        if (classpathElements != null && !classpathElements.isEmpty()) {
            logger.info("Using provided classpath elements: {}", classpathElements.size());
            return classpathElements.stream()
                    .map(Paths::get)
                    .toList();
        } else {
            logger.info("No explicit classpath provided. Type resolution for Java recipes might be limited.");
            return emptyList();
        }
    }

    // Parse resource files (YAML, Properties, XML, TOML)
    private void parseResourceFiles(List<SourceFile> sourceFiles, ExecutionContext ctx) {
        Set<Path> resources = discoverResourceFiles();

        // Parse all resource types if any were found
        if (!resources.isEmpty()) {
            // Parse YAML
            parseResourcesOfType(sourceFiles, resources, "YAML",
                    path -> path.getFileName().toString().endsWith(".yml") || path.getFileName().toString().endsWith(".yaml"),
                    paths -> new YamlParser().parse(paths, baseDir(), ctx),
                    ctx);

            // Parse Properties
            parseResourcesOfType(sourceFiles, resources, "properties",
                    path -> path.getFileName().toString().endsWith(".properties"),
                    paths -> new PropertiesParser().parse(paths, baseDir(), ctx),
                    ctx);

            // Parse XML
            parseResourcesOfType(sourceFiles, resources, "XML",
                    path -> path.getFileName().toString().endsWith(".xml"),
                    paths -> new XmlParser().parse(paths, baseDir(), ctx),
                    ctx);

            // Parse TOML
            parseResourcesOfType(sourceFiles, resources, "TOML",
                    path -> path.getFileName().toString().endsWith(".toml"),
                    paths -> new TomlParser().parse(paths, baseDir(), ctx),
                    ctx);
        } else {
            logger.info("Skipping parsing of resource files as none were discovered or discovery was disabled.");
        }
    }

    // Discover resource files from source paths
    private Set<Path> discoverResourceFiles() {
        if (!discoverResources) {
            logger.info("Skipping resource file discovery (--discover-resources=false).");
            return new HashSet<>();
        }

        if (logger.isInfoEnabled()) {
            logger.info("Discovering resource files (yml, yaml, properties, xml, toml) in: {}",
                    javaSourcePaths.stream().collect(joining(", ")));
        }

        Set<Path> resources = listResourceFiles(javaSourcePaths);
        logger.info("Found {} resource files.", resources.size());
        return resources;
    }

    // Parse Maven POM file if available
    private void parseMavenPom(List<SourceFile> sourceFiles, ExecutionContext ctx) {
        logger.info("Parsing Maven POMs (if found)...");
        try {
            Xml.Document pomAst = parseMaven(ctx);
            if (pomAst != null) {
                sourceFiles.add(pomAst);
                logger.info("Parsed Maven POM: {}", pomAst.getSourcePath());
            } else {
                logger.info("No Maven POM found or parsed in {}", baseDir());
            }
        } catch (Exception e) {
            logger.warn("Failed to parse Maven POM. Skipping. Error: {}", e.getMessage(), e);
        }
    }

    // Execute recipes and filter results
    private ResultsContainer executeRecipesAndGetResults(org.openrewrite.Recipe recipe,
                                                         List<SourceFile> sourceFiles,
                                                         ExecutionContext ctx) {
        logger.info("Running recipe(s) on {} detected source files...", sourceFiles.size());

        // Create source set and run recipe
        LargeSourceSet largeSourceSet = new InMemoryLargeSourceSet(sourceFiles);
        RecipeRun recipeRun = recipe.run(largeSourceSet, ctx);
        List<Result> results = recipeRun.getChangeset().getAllResults();

        // Filter generated files from results
        List<Result> filteredResults = results.stream()
                .filter(source -> {
                    if (source.getBefore() != null) {
                        return !source.getBefore().getMarkers().findFirst(Generated.class).isPresent();
                    }
                    return true;
                }).toList();

        return new ResultsContainer(baseDir(), filteredResults);
    }

    // Process all types of results in a single method
    private void reportAllChanges(ResultsContainer results) {
        if (!results.isNotEmpty()) {
            return;
        }

        reportGeneratedFiles(results);
        reportDeletedFiles(results);
        reportMovedFiles(results);
        reportRefactoredFiles(results);
    }

    void performDryRun() {
        ResultsContainer results = listResults();

        if (!results.isNotEmpty()) {
            return;
        }


        // Report all changes that would be made
        reportAllChanges(results);

        // Write patch file
        writePatchFile(results);

        if (failOnDryRunResults) {
            throw new RewriteExecutionException("Applying recipes would make changes. See logs for more details.");
        }
    }

    // Process generated files
    private void processGeneratedFiles(ResultsContainer results) throws IOException {
        for (Result result : results.generated) {
            if (result.getAfter() != null) {
                logger.warn("Generated new file {} by:",
                        result.getAfter().getSourcePath().normalize());
                logRecipesThatMadeChanges(result);

                Path targetPath = results.getProjectRoot().resolve(result.getAfter().getSourcePath());
                writeFileContent(targetPath, result.getAfter().getCharset(),
                        extractFileContent(result.getAfter()));
            }
        }
    }

    // Unified method to delete a file with error handling
    private void deleteFile(Path projectRoot, SourceFile sourceFile) throws IOException {
        Path originalLocation = projectRoot.resolve(sourceFile.getSourcePath()).normalize();
        try {
            Files.delete(originalLocation);
        } catch (IOException e) {
            throw new IOException(
                    String.format("Unable to delete file %s: %s", originalLocation.toAbsolutePath(), e.getMessage()), e);
        }
    }

    // Process deleted files
    private void processDeletedFiles(ResultsContainer results) throws IOException {
        for (Result result : results.deleted) {
            if (result.getBefore() != null) {
                logger.warn("Deleted file {} by:",
                        result.getBefore().getSourcePath().normalize());
                logRecipesThatMadeChanges(result);
                deleteFile(results.getProjectRoot(), result.getBefore());
            }
        }
    }

    // Unified method to create a file from a SourceFile
    private void createFile(Path projectRoot, SourceFile sourceFile) throws IOException {
        // Ensure directories exist
        Path targetLocation = projectRoot.resolve(sourceFile.getSourcePath());
        createParentDirectories(targetLocation);

        // Write file content
        writeFileContent(targetLocation, sourceFile.getCharset(),
                extractFileContent(sourceFile));
    }

    // Process moved files
    private void processMovedFiles(ResultsContainer results) throws IOException {
        for (Result result : results.moved) {
            if (result.getAfter() == null || result.getBefore() == null) {
                continue; // Skip invalid results
            }

            logger.warn("File has been moved from {} to {} by:",
                    result.getBefore().getSourcePath().normalize(),
                    result.getAfter().getSourcePath().normalize());
            logRecipesThatMadeChanges(result);

            deleteFile(results.getProjectRoot(), result.getBefore());
            createFile(results.getProjectRoot(), result.getAfter());
        }
    }

    private void createParentDirectories(Path filePath) {
        File parentDir = filePath.toFile().getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            logger.warn("Failed to create directory: {}", parentDir);
        }
    }

    // Process files refactored in place
    private void processRefactoredFiles(ResultsContainer results) throws IOException {
        for (Result result : results.refactoredInPlace) {
            if (result.getBefore() != null && result.getAfter() != null) {
                logger.warn("Changes have been made to {} by:",
                        result.getBefore().getSourcePath().normalize());
                logRecipesThatMadeChanges(result);

                Path targetPath = results.getProjectRoot().resolve(result.getBefore().getSourcePath());
                writeFileContent(targetPath, result.getAfter().getCharset(),
                        extractFileContent(result.getAfter()));
            }
        }
    }

    // Apply all file changes from results
    private void applyAllChanges(ResultsContainer results) throws IOException {
        if (!results.isNotEmpty()) {
            return;
        }

        logger.warn("Please review and commit the results.");
        processGeneratedFiles(results);
        processDeletedFiles(results);
        processMovedFiles(results);
        processRefactoredFiles(results);
    }

    void performRun() {
        ResultsContainer results = listResults();
        try {
            applyAllChanges(results);
        } catch (IOException e) {
            throw new RewriteExecutionException("Unable to rewrite source files", e);
        }
    }

    @Override
    public Integer call() {
        if (dryRun) {
            performDryRun();
        } else {
            performRun();
        }

        return 0;
    }

    public static RecipeDescriptor getRecipeDescriptor(String recipe, Collection<RecipeDescriptor> recipeDescriptors) {
        return recipeDescriptors.stream()
                .filter(r -> r.getName().equalsIgnoreCase(recipe))
                .findAny()
                .orElseThrow(() -> new RecipeNotFoundException(recipe));
    }

    @CommandLine.Command(name = "discover")
    static class RewriteDiscover implements Callable<Integer> {

        @CommandLine.ParentCommand
        private Rewrite rewrite; // picocli injects reference to parent command

        /**
         * The name of a specific recipe to show details for. For example:<br>
         * {@code rewrite discover --detail --recipe=org.openrewrite.java.format.AutoFormat}
         */
        @CommandLine.Option(names = "recipe")
        String recipe;

        /**
         * Filter recipes by type/category (matches against package name). For example:<br>
         * {@code rewrite discover --type=java} will show only Java recipes.
         */
        @CommandLine.Option(names = {"--type", "--category"}, description = "Filter recipes by category (java, maven, yaml, etc.)")
        String typeFilter;

        /**
         * Whether to display recipe details such as displayName, description, and
         * configuration options.
         */
        @CommandLine.Option(names = "detail", defaultValue = "false")
        boolean detail;

        /**
         * The maximum level of recursion to display recipe descriptors under
         * recipeList.
         */
        @CommandLine.Option(names = "recursion", defaultValue = "0")
        int recursion;

        @Override
        public Integer call() {
            Environment env = rewrite.environment();
            Collection<RecipeDescriptor> availableRecipeDescriptors = env.listRecipeDescriptors();
            
            // Apply type/category filter if specified
            if (typeFilter != null && !typeFilter.isEmpty()) {
                final String filter = typeFilter.toLowerCase();
                availableRecipeDescriptors = availableRecipeDescriptors.stream()
                    .filter(rd -> categoryMatches(rd.getName(), filter))
                    .collect(java.util.stream.Collectors.toList());
                    
                System.out.println("\nFiltering by category: " + LoggingUtils.formatJavaName("org.openrewrite." + filter, rewrite.noColor));
            }
            
            if (recipe != null) {
                RecipeDescriptor rd = getRecipeDescriptor(recipe, availableRecipeDescriptors);
                writeRecipeDescriptor(rd, detail, 0, 0);
            } else {
                Collection<RecipeDescriptor> activeRecipeDescriptors = new HashSet<>();
                for (String activeRecipe : rewrite.activeRecipes) {
                    RecipeDescriptor rd = getRecipeDescriptor(activeRecipe, availableRecipeDescriptors);
                    activeRecipeDescriptors.add(rd);
                }
                writeDiscovery(availableRecipeDescriptors, activeRecipeDescriptors, env.listStyles());
            }
            return 0;
        }
        
        /**
         * Check if a recipe name matches the specified category filter
         */
        private boolean categoryMatches(String recipeName, String filter) {
            // Special case for "all" to show all recipes
            if ("all".equalsIgnoreCase(filter)) {
                return true;
            }
            
            // Extract category from recipe name based on package structure
            // E.g. org.openrewrite.java.format.AutoFormat -> "java"
            String[] parts = recipeName.split("\\.");
            
            // Most recipes follow the pattern org.openrewrite.CATEGORY...
            if (parts.length >= 3 && "openrewrite".equals(parts[1])) {
                return parts[2].toLowerCase().equals(filter);
            }
            
            // Special case handling for specific keywords
            return recipeName.toLowerCase().contains(filter);
        }

        private void writeDiscovery(Collection<RecipeDescriptor> availableRecipeDescriptors,
                                    Collection<RecipeDescriptor> activeRecipeDescriptors, Collection<NamedStyles> availableStyles) {

            writeAvailableRecipes(availableRecipeDescriptors);
            writeAvailableStyles(availableStyles);
            writeActiveStyles();
            writeActiveRecipes(activeRecipeDescriptors);
            writeCategories(availableRecipeDescriptors);
            writeSummary(availableRecipeDescriptors, availableStyles, activeRecipeDescriptors);
        }
        
        /**
         * Write out available categories extracted from recipe names
         */
        private void writeCategories(Collection<RecipeDescriptor> availableRecipeDescriptors) {
            System.out.println();
            rewrite.printColored("Available Categories", LoggingUtils.STYLE_HEADING);
            System.out.println();
            
            // Extract all categories from recipe names
            java.util.Set<String> categories = new java.util.TreeSet<>();
            for (RecipeDescriptor rd : availableRecipeDescriptors) {
                String[] parts = rd.getName().split("\\.");
                if (parts.length >= 3 && "openrewrite".equals(parts[1])) {
                    categories.add(parts[2]);
                }
            }
            
            // Print sorted categories with counts
            for (String category : categories) {
                // Count recipes in this category
                long count = availableRecipeDescriptors.stream()
                    .filter(rd -> categoryMatches(rd.getName(), category))
                    .count();
                
                String formattedCategory = LoggingUtils.formatJavaName("org.openrewrite." + category, rewrite.noColor);
                rewrite.printIndented(formattedCategory + " (" + count + " recipes)", LoggingUtils.STYLE_RECIPE, 1);
            }
            
            // Print help text for filtering if not already filtered
            if (typeFilter == null) {
                System.out.println();
                rewrite.printIndented("Use --type=<category> to filter recipes by category", LoggingUtils.STYLE_HIGHLIGHT, 1);
            }
        }
        
        private void writeAvailableRecipes(Collection<RecipeDescriptor> availableRecipeDescriptors) {
            rewrite.printColored("Available Recipes", LoggingUtils.STYLE_HEADING);
            System.out.println();
            for (RecipeDescriptor recipeDescriptor : availableRecipeDescriptors) {
                writeRecipeDescriptor(recipeDescriptor, detail, 0, 1);
            }
        }

        private void writeAvailableStyles(Collection<NamedStyles> availableStyles) {
            System.out.println();
            rewrite.printColored("Available Styles", LoggingUtils.STYLE_HEADING);
            System.out.println();
            for (NamedStyles style : availableStyles) {
                String formattedName = LoggingUtils.formatJavaName(style.getName(), rewrite.noColor);
                rewrite.printIndented(formattedName, LoggingUtils.STYLE_RECIPE, 1);
            }
        }

        private void writeActiveStyles() {
            System.out.println();
            rewrite.printColored("Active Styles", LoggingUtils.STYLE_HEADING);
            System.out.println();
            for (String activeStyle : rewrite.activeStyles) {
                String formattedName = LoggingUtils.formatJavaName(activeStyle, rewrite.noColor);
                rewrite.printIndented(formattedName, LoggingUtils.STYLE_RECIPE_ACTIVE, 1);
            }
        }

        private void writeActiveRecipes(Collection<RecipeDescriptor> activeRecipeDescriptors) {
            System.out.println();
            rewrite.printColored("Active Recipes", LoggingUtils.STYLE_HEADING);
            System.out.println();
            for (RecipeDescriptor rd : activeRecipeDescriptors) {
                writeRecipeDescriptor(rd, detail, 0, 1);
            }
        }

        private void writeSummary(Collection<RecipeDescriptor> availableRecipeDescriptors,
                                  Collection<NamedStyles> availableStyles,
                                  Collection<RecipeDescriptor> activeRecipeDescriptors) {
            System.out.println();
            rewrite.printColored("Summary", LoggingUtils.STYLE_HEADING);
            rewrite.printIndented(
                    String.format("Found %d available recipes and %d available styles.",
                            availableRecipeDescriptors.size(), availableStyles.size()),
                    LoggingUtils.STYLE_SUCCESS, 1);
            rewrite.printIndented(
                    String.format("Configured with %d active recipes and %d active styles.",
                            activeRecipeDescriptors.size(), rewrite.activeStyles.size()),
                    LoggingUtils.STYLE_HIGHLIGHT, 1);
        }

        private void writeRecipeDescriptor(RecipeDescriptor rd, boolean verbose, int currentRecursionLevel,
                                           int indentLevel) {
            // Early return if recursion level is exceeded
            if (currentRecursionLevel > recursion) {
                return;
            }
            
            StringBuilder recipeInfo = new StringBuilder(LoggingUtils.formatJavaName(rd.getName(), rewrite.noColor));
            
            // Add a check mark to indicate this is the active recipe
            if (rewrite.activeRecipes.contains(rd.getName())) {
                recipeInfo.append(" ").append(LoggingUtils.SYMBOL_SUCCESS);
            }
            
            // Use active recipe style if it's active, otherwise regular recipe style
            String style = rewrite.activeRecipes.contains(rd.getName()) ? LoggingUtils.STYLE_RECIPE_ACTIVE : LoggingUtils.STYLE_RECIPE;
            rewrite.printIndented(recipeInfo.toString(), style, indentLevel);
            
            if (verbose) {
                writeVerboseRecipeInfo(rd, indentLevel + 1);
            }
            
            writeRecipeListIfNeeded(rd, verbose, currentRecursionLevel, indentLevel + 1);
        }

        private void writeVerboseRecipeInfo(RecipeDescriptor rd, int indentLevel) {
            // Display name as heading at current indent level
            rewrite.printIndented(rd.getDisplayName(), LoggingUtils.STYLE_HEADING, indentLevel);

            // Recipe name with active indicator if applicable
            String style = rewrite.activeRecipes.contains(rd.getName()) ? LoggingUtils.STYLE_RECIPE_ACTIVE : LoggingUtils.STYLE_RECIPE;
            rewrite.printIndented(rd.getName(), style, indentLevel + 1);

            // Description as plain text
            String description = rd.getDescription();
            if (description != null && !description.isEmpty()) {
                rewrite.printIndented(description, LoggingUtils.STYLE_INFO, indentLevel + 1);
            }

            writeOptionsIfPresent(rd, indentLevel);

            // Add blank line after verbose output
            System.out.println();
        }

        private void writeOptionsIfPresent(RecipeDescriptor rd, int indentLevel) {
            if (rd.getOptions().isEmpty()) {
                return;
            }

            rewrite.printIndented("Options:", LoggingUtils.STYLE_WARNING, indentLevel + 1);
            for (OptionDescriptor od : rd.getOptions()) {
                writeOptionInfo(od, indentLevel + 2);
            }
        }

        private void writeOptionInfo(OptionDescriptor od, int indentLevel) {
            String required = od.isRequired() ? " (required)" : "";
            String optionText = String.format("%s: %s%s", od.getName(), od.getType(), required);
            String style = od.isRequired() ? LoggingUtils.STYLE_ERROR : LoggingUtils.STYLE_RECIPE;

            rewrite.printIndented(optionText, style, indentLevel);

            if (od.getDescription() != null && !od.getDescription().isEmpty()) {
                rewrite.printIndented(od.getDescription(), LoggingUtils.STYLE_INFO, indentLevel + 1);
            }
        }

        private void writeRecipeListIfNeeded(RecipeDescriptor rd, boolean verbose, int currentRecursionLevel,
                                             int indentLevel) {
            boolean hasRecipeList = !rd.getRecipeList().isEmpty();
            boolean withinRecursionLimit = (currentRecursionLevel + 1 <= recursion);

            if (hasRecipeList && withinRecursionLimit) {
                rewrite.printIndented("Includes:", LoggingUtils.STYLE_WARNING, indentLevel + 1);
                for (RecipeDescriptor r : rd.getRecipeList()) {
                    writeRecipeDescriptor(r, verbose, currentRecursionLevel + 1, indentLevel + 2);
                }
            }
        }

    }

    // Helper method to write file content
    private void writeFileContent(Path targetPath, Charset charset, String content) throws IOException {
        try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(targetPath)) {
            sourceFileWriter.write(content);
        }
    }

    // Custom exception classes
    static class RewriteExecutionException extends RuntimeException {
        public RewriteExecutionException(String message) {
            super(message);
        }

        public RewriteExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static class RecipeNotFoundException extends IllegalStateException {
        public RecipeNotFoundException(String recipe) {
            super(String.format("Could not find recipe '%s' among available recipes", recipe));
        }
    }

}
