///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//COMPILE_OPTIONS -Xlint:deprecation -Xlint:unchecked -proc:none

//DEPS info.picocli:picocli:4.7.6
//DEPS org.slf4j:slf4j-simple:2.0.17
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

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.joining;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
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

@Command(name = "rewrite", mixinStandardHelpOptions = true, version = "rewrite 0.2", description = "rewrite made with jbang", subcommands = Rewrite.RewriteDiscover.class)
class Rewrite implements Callable<Integer> {

    private static final String RECIPE_NOT_FOUND_EXCEPTION_MSG = "Could not find recipe '%s' among available recipes";
    private static final String INDENT_SPACES = "    ";
    private static final String FALSE_VALUE = "false";

    // Singleton instance for static method access
    private static final Rewrite INSTANCE = new Rewrite();

    // SLF4J Logger - making it public static so inner classes can access it
    public static final Logger logger = LoggerFactory.getLogger(Rewrite.class);

    public static Rewrite getInstance() {
        return INSTANCE;
    }

    // Private constructor to enforce singleton pattern
    private Rewrite() {
        // Private constructor to prevent direct instantiation
    }

    @Option(names = { "--baseDir",
            "--base-dir" }, description = "Base directory for the project. Defaults to current directory.")
    private String baseDirPath = ".";

    private Path baseDir() {
        return Path.of(baseDirPath).toAbsolutePath().normalize();
    }

    @Option(names = "--recipes", split = ",")
    Set<String> activeRecipes = emptySet();

    @Option(names = "--styles", split = ",")
    protected Set<String> activeStyles = Collections.emptySet();

    @Option(names = { "--javaSources", "--java-sources" }, defaultValue = ".", split = ",")
    List<String> javaSourcePaths = emptyList();

    @Option(names = "--discover-resources", defaultValue = "true", description = "Attempt to discover resource files (yml, xml, properties) in source directories.")
    boolean discoverResources;

    @Option(names = "--classpath", description = "Specify the classpath for type resolution, using the system path separator.", split = "${sys:path.separator}")
    List<String> classpathElements = emptyList();

    @Option(names = { "--failOnInvalidActiveRecipes", "--fail-on-invalid-recipes" }, defaultValue = "false")
    boolean failOnInvalidActiveRecipes;

    @Option(names = { "--reportOutputDirectory", "--report" }, defaultValue = "./rewrite")
    private File reportOutputDirectory;

    @Option(names = { "--failOnDryRunResults", "--fail-on-dry-run" }, defaultValue = "false")
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

    public static void main(String... args) {
        // Configure slf4j-simple to format output similar to previous implementation
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");
        System.setProperty("org.slf4j.simpleLogger.showLogName", "false");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "false");

        // Suppress warnings from ReloadableJava11Parser
        System.setProperty("org.slf4j.simpleLogger.log.org.openrewrite.java.isolated.ReloadableJava11Parser", "ERROR");

        int exitCode = new CommandLine(new Rewrite()).execute(args);
        System.exit(exitCode);
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
                .map(it -> normalizePathSafely(it))
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
                        .map(rd -> rd.getName())
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

    Rewrite.ResultsContainer listResults() {
        var env = environment();

        if (activeRecipes.isEmpty()) {
            logger.warn(
                    "No recipes specified. Activate a recipe on the command line with '--recipes com.fully.qualified.RecipeClassName'");
            return new ResultsContainer(baseDir(), emptyList());
        }

        var recipe = activateRecipesWithFallback(env);
        if (recipe.getRecipeList().isEmpty() && activeRecipes.isEmpty()) {
            return new ResultsContainer(baseDir(), emptyList());
        }

        List<NamedStyles> styles = env.activateStyles(activeStyles);
        logger.info("");

        ExecutionContext ctx = executionContext();

        logger.info("Validating active recipes...");
        Validated validated = recipe.validate(ctx);
        @SuppressWarnings("unchecked")
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

        List<Path> javaSources = new ArrayList<>();
        javaSourcePaths.forEach(path -> javaSources.addAll(listJavaSources(path)));

        if (logger.isInfoEnabled()) {
            logger.info("Parsing Java files found in: {}", javaSourcePaths.stream().collect(joining(", ")));
        }

        // Prepare classpath
        List<Path> classpath = emptyList();
        if (classpathElements != null && !classpathElements.isEmpty()) {
            logger.info("Using provided classpath elements: {}", classpathElements.size());
            classpath = classpathElements.stream()
                    .map(Paths::get)
                    .toList();
        } else {
            logger.info("No explicit classpath provided. Type resolution for Java recipes might be limited.");
        }

        List<SourceFile> sourceFiles = parseJavaSources(javaSources, classpath, styles, ctx);

        Set<Path> resources = new HashSet<>();
        if (discoverResources) {
            if (logger.isInfoEnabled()) {
                logger.info("Discovering resource files (yml, yaml, properties, xml, toml) in: {}",
                        javaSourcePaths.stream().collect(joining(", ")));
            }
            resources = listResourceFiles(javaSourcePaths);
            logger.info("Found {} resource files.", resources.size());
        } else {
            logger.info("Skipping resource file discovery (--discover-resources=false).");
        }

        // Always attempt to parse supported types if resources were found/discovered
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
            logger.info(
                    "Skipping parsing of YAML, Properties, XML, and TOML files as no resources were discovered or discovery was disabled.");
        }

        // Always attempt to parse Maven POM (typically pom.xml at baseDir)
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

        logger.info("Running recipe(s) on {} detected source files...", sourceFiles.size());
        // Use InMemoryLargeSourceSet and RecipeRun based on plugin v5.39.2
        LargeSourceSet largeSourceSet = new InMemoryLargeSourceSet(sourceFiles);
        RecipeRun recipeRun = recipe.run(largeSourceSet, ctx);
        List<Result> results = recipeRun.getChangeset().getAllResults();

        // Filter results after running the recipe
        List<Result> filteredResults = results.stream()
                .filter(source -> {
                    if (source.getBefore() != null) {
                        return !source.getBefore().getMarkers().findFirst(Generated.class).isPresent();
                    }
                    return true;
                }).toList();

        return new ResultsContainer(baseDir(), filteredResults);
    }

    // log method to mimic plugin behavior
    protected void log(LogLevel logLevel, CharSequence content) {
        switch (logLevel) {
            case DEBUG:
                // Map DEBUG to INFO for now
                if (logger.isInfoEnabled()) {
                    logger.info(content.toString());
                }
                break;
            case INFO:
                if (logger.isInfoEnabled()) {
                    logger.info(content.toString());
                }
                break;
            case WARN:
                if (logger.isWarnEnabled()) {
                    logger.warn(content.toString());
                }
                break;
            case ERROR:
                if (logger.isErrorEnabled()) {
                    logger.error(content.toString());
                }
                break;
        }
    }

    // Updated Source URL
    // Source:
    // https://sourcegraph.com/github.com/openrewrite/rewrite-maven-plugin@v5.40.0/-/blob/src/main/java/org/openrewrite/maven/AbstractRewriteBaseRunMojo.java?L461-469
    protected void logRecipesThatMadeChanges(Result result) {
        String indent = INDENT_SPACES;
        // Use a fixed size for prefix to avoid string concatenation in a loop
        StringBuilder prefix = new StringBuilder(INDENT_SPACES);
        for (RecipeDescriptor recipeDescriptor : result.getRecipeDescriptorsThatMadeChanges()) {
            logRecipe(recipeDescriptor, prefix.toString());
            prefix.append(indent);
        }
    }

    // Updated Source URL
    // Source:
    // https://sourcegraph.com/github.com/openrewrite/rewrite-maven-plugin@v5.40.0/-/blob/src/main/java/org/openrewrite/maven/AbstractRewriteBaseRunMojo.java?L471-489
    private void logRecipe(RecipeDescriptor rd, String prefix) {
        log(recipeChangeLogLevel, buildRecipeLogMessage(rd, prefix));
        logChildRecipes(rd, prefix);
    }
    
    // Extract recipe message building
    private String buildRecipeLogMessage(RecipeDescriptor rd, String prefix) {
        StringBuilder recipeString = new StringBuilder(prefix + rd.getName());
        
        String options = formatRecipeOptions(rd);
        if (!options.isEmpty()) {
            recipeString.append(": {").append(options).append("}");
        }
        
        return recipeString.toString();
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
    
    // Extract child recipe logging
    private void logChildRecipes(RecipeDescriptor rd, String prefix) {
        if (rd.getRecipeList().isEmpty()) {
            return;
        }
        
        String childPrefix = prefix + INDENT_SPACES;
        for (RecipeDescriptor childRecipe : rd.getRecipeList()) {
            logRecipe(childRecipe, childPrefix);
        }
    }

    // Extract method to report generated files
    private void reportGeneratedFiles(ResultsContainer results) {
        for (Result result : results.generated) {
            if (result.getAfter() != null) {
                logger.warn("These recipes would generate new file {}:", result.getAfter().getSourcePath());
                logRecipesThatMadeChanges(result);
            }
        }
    }
    
    // Extract method to report deleted files
    private void reportDeletedFiles(ResultsContainer results) {
        for (Result result : results.deleted) {
            if (result.getBefore() != null) {
                logger.warn("These recipes would delete file {}:", result.getBefore().getSourcePath());
                logRecipesThatMadeChanges(result);
            }
        }
    }
    
    // Extract method to report moved files
    private void reportMovedFiles(ResultsContainer results) {
        for (Result result : results.moved) {
            if (result.getBefore() != null && result.getAfter() != null) {
                logger.warn("These recipes would move file from {} to {}:",
                        result.getBefore().getSourcePath(), result.getAfter().getSourcePath());
                logRecipesThatMadeChanges(result);
            }
        }
    }
    
    // Extract method to report refactored files
    private void reportRefactoredFiles(ResultsContainer results) {
        for (Result result : results.refactoredInPlace) {
            if (result.getBefore() != null) {
                logger.warn("These recipes would make changes to {}:", result.getBefore().getSourcePath());
                logRecipesThatMadeChanges(result);
            }
        }
    }
    
    // Extract method to write patch file
    private void writePatchFile(ResultsContainer results) {
        // Check return value of mkdirs()
        if (!reportOutputDirectory.mkdirs() && !reportOutputDirectory.exists()) {
            logger.warn("Failed to create directory: {}", reportOutputDirectory);
        }

        Path patchFile = reportOutputDirectory.toPath().resolve("rewrite.patch");
        try (BufferedWriter writer = Files.newBufferedWriter(patchFile)) {
            Stream.concat(Stream.concat(results.generated.stream(), results.deleted.stream()),
                    Stream.concat(results.moved.stream(), results.refactoredInPlace.stream())).map(Result::diff)
                    .forEach(diff -> {
                        try {
                            writer.write(diff + "\n");
                        } catch (IOException e) {
                            throw new RewriteExecutionException("Failed to write diff", e);
                        }
                    });
        } catch (Exception e) {
            throw new RewriteExecutionException("Unable to generate rewrite result file", e);
        }
        
        logger.warn("Report available:");
        logger.warn("    {}", patchFile.normalize());
    }

    void performDryRun() {
        ResultsContainer results = listResults();

        if (!results.isNotEmpty()) {
            return;
        }
        
        // Report all changes that would be made
        reportGeneratedFiles(results);
        reportDeletedFiles(results);
        reportMovedFiles(results);
        reportRefactoredFiles(results);
        
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
                Charset charset = result.getAfter().getCharset();
                String content = new String(result.getAfter().printAll().getBytes(charset), charset);
                writeFileContent(targetPath, charset, content);
            }
        }
    }
    
    // Process deleted files
    private void processDeletedFiles(ResultsContainer results) throws IOException {
        for (Result result : results.deleted) {
            if (result.getBefore() != null) {
                logger.warn("Deleted file {} by:",
                        result.getBefore().getSourcePath().normalize());
                logRecipesThatMadeChanges(result);
                
                Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath())
                        .normalize();
                try {
                    Files.delete(originalLocation);
                } catch (IOException e) {
                    throw new IOException(
                            String.format("Unable to delete file %s: %s", originalLocation.toAbsolutePath(), e.getMessage()), e);
                }
            }
        }
    }
    
    // Process moved files
    private void processMovedFiles(ResultsContainer results) throws IOException {
        for (Result result : results.moved) {
            if (result.getAfter() != null && result.getBefore() != null) {
                logger.warn("File has been moved from {} to {} by:",
                        result.getBefore().getSourcePath().normalize(),
                        result.getAfter().getSourcePath().normalize());
                logRecipesThatMadeChanges(result);
                
                // Delete original file
                if (result.getBefore() != null) {
                    Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath());
                    try {
                        Files.delete(originalLocation);
                    } catch (IOException e) {
                        throw new IOException(
                                String.format("Unable to delete file %s: %s", originalLocation.toAbsolutePath(), e.getMessage()), e);
                    }
                }
                
                // Create new file
                if (result.getAfter() != null) {
                    // Ensure directories exist
                    Path afterLocation = results.getProjectRoot().resolve(result.getAfter().getSourcePath());
                    File parentDir = afterLocation.toFile().getParentFile();

                    if (!parentDir.exists() && !parentDir.mkdirs()) {
                        logger.warn("Failed to create directory: {}", parentDir);
                    }

                    Charset charset = result.getAfter().getCharset();
                    String content = new String(result.getAfter().printAll().getBytes(charset), charset);
                    writeFileContent(afterLocation, charset, content);
                }
            }
        }
    }
    
    // Process files refactored in place
    private void processRefactoredFiles(ResultsContainer results) throws IOException {
        for (Result result : results.refactoredInPlace) {
            if (result.getBefore() != null) {
                logger.warn("Changes have been made to {} by:",
                        result.getBefore().getSourcePath().normalize());
                logRecipesThatMadeChanges(result);
                
                if (result.getAfter() != null) {
                    Path targetPath = results.getProjectRoot().resolve(result.getBefore().getSourcePath());
                    Charset charset = result.getAfter().getCharset();
                    String content = new String(result.getAfter().printAll().getBytes(charset), charset);
                    writeFileContent(targetPath, charset, content);
                }
            }
        }
    }

    void realrun() {
        ResultsContainer results = listResults();

        if (results.isNotEmpty()) {
            logger.warn("Please review and commit the results.");

            try {
                processGeneratedFiles(results);
                processDeletedFiles(results);
                processMovedFiles(results);
                processRefactoredFiles(results);
            } catch (IOException e) {
                throw new RewriteExecutionException("Unable to rewrite source files", e);
            }
        }
    }

    @Override
    public Integer call() { // your business logic goes here...

        if (dryRun) {
            performDryRun();
        } else {
            realrun();
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
        @Option(names = "recipe")
        String recipe;

        /**
         * Whether to display recipe details such as displayName, description, and
         * configuration options.
         */
        @Option(names = "detail", defaultValue = "false")
        boolean detail;

        /**
         * The maximum level of recursion to display recipe descriptors under
         * recipeList.
         */
        @Option(names = "recursion", defaultValue = "0")
        int recursion;

        @Override
        public Integer call() {
            Environment env = rewrite.environment();
            Collection<RecipeDescriptor> availableRecipeDescriptors = env.listRecipeDescriptors();
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

        private void writeDiscovery(Collection<RecipeDescriptor> availableRecipeDescriptors,
                Collection<RecipeDescriptor> activeRecipeDescriptors, Collection<NamedStyles> availableStyles) {
            logger.info("Available Recipes:");
            for (RecipeDescriptor recipeDescriptor : availableRecipeDescriptors) {
                writeRecipeDescriptor(recipeDescriptor, detail, 0, 1);
            }

            logger.info("");
            logger.info("Available Styles:");
            for (NamedStyles style : availableStyles) {
                logger.info("    {}", style.getName());
            }

            logger.info("");
            logger.info("Active Styles:");
            for (String activeStyle : rewrite.activeStyles) {
                logger.info("    {}", activeStyle);
            }

            logger.info("");
            logger.info("Active Recipes:");
            for (RecipeDescriptor recipeDescriptor : activeRecipeDescriptors) {
                writeRecipeDescriptor(recipeDescriptor, detail, 0, 1);
            }

            logger.info("");
            logger.info("Found {} available recipes and {} available styles.",
                    availableRecipeDescriptors.size(), availableStyles.size());
            logger.info("Configured with {} active recipes and {} active styles.",
                    activeRecipeDescriptors.size(), rewrite.activeStyles.size());
        }

        private void writeRecipeDescriptor(RecipeDescriptor rd, boolean verbose, int currentRecursionLevel,
                int indentLevel) {
            // Early return if recursion level is exceeded
            if (currentRecursionLevel > recursion) {
                return;
            }
            
            String indent = StringUtils.repeat(INDENT_SPACES, indentLevel * 4);
            
            if (verbose) {
                writeVerboseRecipeInfo(rd, indent);
            } else {
                logger.info("{}{}", indent, rd.getName());
            }

            writeRecipeListIfNeeded(rd, verbose, currentRecursionLevel, indentLevel, indent);
        }
        
        private void writeVerboseRecipeInfo(RecipeDescriptor rd, String indent) {
            logger.info("{}{}", indent, rd.getDisplayName());
            logger.info("{}    {}", indent, rd.getName());
            
            writeDescriptionIfPresent(rd, indent);
            writeOptionsIfPresent(rd, indent);
            
            // Add blank line after verbose output
            logger.info("");
        }
        
        private void writeDescriptionIfPresent(RecipeDescriptor rd, String indent) {
            String description = rd.getDescription();
            if (description != null && !description.isEmpty()) {
                logger.info("{}    {}", indent, description);
            }
        }
        
        private void writeOptionsIfPresent(RecipeDescriptor rd, String indent) {
            if (rd.getOptions().isEmpty()) {
                return;
            }
            
            logger.info("{}options: ", indent);
            for (OptionDescriptor od : rd.getOptions()) {
                writeOptionInfo(od, indent);
            }
        }
        
        private void writeOptionInfo(OptionDescriptor od, String indent) {
            logger.info("{}    {}: {}{}",
                    indent,
                    od.getName(),
                    od.getType(),
                    od.isRequired() ? "!" : "");
                    
            if (od.getDescription() != null && !od.getDescription().isEmpty()) {
                logger.info("{}        {}", indent, od.getDescription());
            }
        }
        
        private void writeRecipeListIfNeeded(RecipeDescriptor rd, boolean verbose, int currentRecursionLevel,
                                           int indentLevel, String indent) {
            boolean hasRecipeList = !rd.getRecipeList().isEmpty();
            boolean withinRecursionLimit = (currentRecursionLevel + 1 <= recursion);
            
            if (hasRecipeList && withinRecursionLimit) {
                logger.info("{}recipeList:", indent);
                for (RecipeDescriptor r : rd.getRecipeList()) {
                    writeRecipeDescriptor(r, verbose, currentRecursionLevel + 1, indentLevel + 1);
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
