///usr/bin/env jbang "$0" "$@" ; exit $?
//COMPILE_OPTIONS -Xlint:deprecation -Xlint:unchecked

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



import static java.lang.System.err;
import static java.lang.System.out;
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
        return new InMemoryExecutionContext(t -> {
            logger.warn("Error during recipe execution: {}", t.getMessage(), t);
        });
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

        // Provide a default local repository path if not set
        File localRepoPath = mer.getLocalRepositoryPath();
        String localRepo = (localRepoPath != null)
                ? localRepoPath.toString()
                : Paths.get(System.getProperty("user.home"), ".m2", "repository").toString();

        MavenSettings.Profiles profiles = new MavenSettings.Profiles();
        profiles.setProfiles(
                mer.getProfiles().stream().map(p -> {
                    // Extract nested ternary operation
                    ProfileActivation.Property activationProperty = null;
                    if (p.getActivation() != null && p.getActivation().getProperty() != null) {
                        activationProperty = new ProfileActivation.Property(
                                p.getActivation().getProperty().getName(),
                                p.getActivation().getProperty().getValue());
                    }
                    
                    return new MavenSettings.Profile(
                            p.getId(),
                            p.getActivation() == null ? null
                                    : new ProfileActivation(
                                    p.getActivation().isActiveByDefault(),
                                    p.getActivation().getJdk(),
                                    activationProperty),
                            buildRawRepositories(p.getRepositories()));
                }).toList());

        MavenSettings.ActiveProfiles activeProfiles = new MavenSettings.ActiveProfiles();
        List<String> merActiveProfiles = mer.getActiveProfiles();
        activeProfiles.setActiveProfiles(merActiveProfiles != null ? merActiveProfiles : Collections.emptyList());

        MavenSettings.Mirrors mirrors = new MavenSettings.Mirrors();
        mirrors.setMirrors(
                mer.getMirrors().stream().map(m -> new MavenSettings.Mirror(
                        m.getId(),
                        m.getUrl(),
                        m.getMirrorOf(),
                        null,
                        null)).toList());

        MavenSettings.Servers servers = new MavenSettings.Servers();
        servers.setServers(emptyList());

        return new MavenSettings(localRepo, profiles, activeProfiles, mirrors, servers);
    }

    public Xml.Document parseMaven(ExecutionContext ctx) {
        // Explicitly look for pom.xml in the base directory
        Path pomPath = baseDir().resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            // Optional: Log if no pom.xml is found at the expected location
            // getLog().info("No pom.xml found in base directory: " + baseDir());
            return null; // Return null if pom.xml doesn't exist
        }
        List<Path> pomToParse = Collections.singletonList(pomPath);

        MavenParser.Builder mavenParserBuilder = MavenParser.builder();

        MavenSettings settings = buildSettings();
        MavenExecutionContextView mavenExecutionContext = MavenExecutionContextView.view(ctx);
        mavenExecutionContext.setMavenSettings(settings);

        if (settings.getActiveProfiles() != null && 
            !settings.getActiveProfiles().getActiveProfiles().isEmpty()) {
            mavenParserBuilder.activeProfiles(settings.getActiveProfiles().getActiveProfiles().toArray(new String[0]));
        }

        // Parse the explicitly found pom.xml - Correct variable type
        List<SourceFile> parsedPoms = mavenParserBuilder
                .build()
                .parse(pomToParse, baseDir(), ctx)
                .toList();

        // Find the Xml.Document within the SourceFile stream/list
        return parsedPoms.stream()
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
            File sourceDirectoryFile = new File(sourceDir);
            if (!sourceDirectoryFile.exists() || !sourceDirectoryFile.isDirectory()) {
                continue;
            }
            Path sourceRoot = sourceDirectoryFile.toPath();
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                walk.filter(p -> !Files.isDirectory(p))
                        .filter(p -> {
                            String fileName = p.getFileName().toString();
                            return resourceExtensions.stream().anyMatch(fileName::endsWith);
                        })
                        .map(it -> {
                            try {
                                return it.toRealPath().normalize();
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .forEach(resourceFiles::add);
            } catch (IOException e) {
                logger.warn("Could not scan directory for resources: {} - {}", sourceRoot, e.getMessage());
            }
        }
        return resourceFiles;
    }

    public static class ResultsContainer {
        final Path projectRoot;
        final List<Result> generated = new ArrayList<>();
        final List<Result> deleted = new ArrayList<>();
        final List<Result> moved = new ArrayList<>();
        final List<Result> refactoredInPlace = new ArrayList<>();

        public ResultsContainer(Path projectRoot, Collection<Result> results) {
            this.projectRoot = projectRoot;
            for (Result result : results) {
                if (result.getBefore() == null && result.getAfter() == null) {
                    // This situation shouldn't happen / makes no sense, log and skip
                    continue;
                }
                if (result.getBefore() == null && result.getAfter() != null) {
                    generated.add(result);
                } else if (result.getBefore() != null && result.getAfter() == null) {
                    deleted.add(result);
                } else if (result.getBefore() != null
                        && !result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())) {
                    moved.add(result);
                } else {
                    refactoredInPlace.add(result);
                }
            }
        }

        public Path getProjectRoot() {
            return projectRoot;
        }

        public boolean isNotEmpty() {
            return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
        }
    }

    Rewrite.ResultsContainer listResults() {
        var env = environment();

        if (activeRecipes.isEmpty()) {
            logger.warn(
                    "No recipes specified. Activate a recipe on the command line with '--recipes com.fully.qualified.RecipeClassName'");
            return new ResultsContainer(baseDir(), emptyList());
        }

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
                recipe = env.activateRecipes(names);
            } else {
                logger.warn("No matching recipes found for specified active recipes: {}", activeRecipes);
                return new ResultsContainer(baseDir(), emptyList());
            }
        }

        List<NamedStyles> styles;
        styles = env.activateStyles(activeStyles);

        logger.info("");

        ExecutionContext ctx = executionContext();

        logger.info("Validating active recipes...");
        Validated validated = recipe.validate(ctx);
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
                logger.error("Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless.");
            }
        }

        List<Path> javaSources = new ArrayList<>();
        javaSourcePaths.forEach(path -> javaSources.addAll(listJavaSources(path)));

        logger.info("Parsing Java files found in: {}", javaSourcePaths.stream().collect(joining(", ")));

        // Prepare classpath
        List<Path> classpath = emptyList();
        if (classpathElements != null && !classpathElements.isEmpty()) {
            logger.info("Using provided classpath elements: {}", classpathElements.size());
            classpath = classpathElements.stream()
                    .map(Paths::get)
                    .toList();
        } else {
            logger.info("No explicit classpath provided. Type resolution for Java recipes might be limited.");
            // Consider adding a warning or a way to auto-detect later if needed
        }

        List<SourceFile> sourceFiles = new ArrayList<>();

        // Parse Java - Collect Stream<SourceFile> to List
        sourceFiles.addAll(
                JavaParser.fromJavaVersion()
                        .styles(styles)
                        .classpath(classpath)
                        .logCompilationWarningsAndErrors(true).build().parse(javaSources, baseDir(), ctx)
                        .toList());
        logger.info("{} java files parsed.", sourceFiles.size());

        Set<Path> resources = new HashSet<>();
        if (discoverResources) {
            logger.info("Discovering resource files (yml, yaml, properties, xml, toml) in: {}",
                    javaSourcePaths.stream().collect(joining(", ")));
            resources = listResourceFiles(javaSourcePaths);
            logger.info("Found {} resource files.", resources.size());
        } else {
            logger.info("Skipping resource file discovery (--discover-resources=false).");
        }

        // Always attempt to parse supported types if resources were found/discovered
        if (!resources.isEmpty()) {
            logger.info("Parsing YAML files...");
            List<Path> yamlPaths = resources.stream()
                    .filter(it -> it.getFileName().toString().endsWith(".yml")
                            || it.getFileName().toString().endsWith(".yaml"))
                    .toList();
            if (!yamlPaths.isEmpty()) {
                // Collect Stream<SourceFile> to List
                sourceFiles.addAll(
                        new YamlParser().parse(yamlPaths, baseDir(), ctx)
                                .toList());
                logger.info("Parsed {} YAML files.", yamlPaths.size());
            } else {
                logger.info("No YAML files found to parse.");
            }

            logger.info("Parsing properties files...");
            List<Path> propertiesPaths = resources.stream()
                    .filter(it -> it.getFileName().toString().endsWith(".properties")).toList();
            if (!propertiesPaths.isEmpty()) {
                // Collect Stream<SourceFile> to List
                sourceFiles.addAll(
                        new PropertiesParser().parse(propertiesPaths, baseDir(), ctx)
                                .toList());
                logger.info("Parsed {} properties files.", propertiesPaths.size());
            } else {
                logger.info("No properties files found to parse.");
            }

            logger.info("Parsing XML files...");
            List<Path> xmlPaths = resources.stream().filter(it -> it.getFileName().toString().endsWith(".xml"))
                    .toList();
            if (!xmlPaths.isEmpty()) {
                // Collect Stream<SourceFile> to List
                sourceFiles.addAll(
                        new XmlParser().parse(xmlPaths, baseDir(), ctx)
                                .toList());
                logger.info("Parsed {} XML files.", xmlPaths.size());
            } else {
                logger.info("No XML files found to parse.");
            }

            logger.info("Parsing TOML files...");
            List<Path> tomlPaths = resources.stream().filter(it -> it.getFileName().toString().endsWith(".toml"))
                    .toList();
            if (!tomlPaths.isEmpty()) {
                // Collect Stream<SourceFile> to List
                sourceFiles.addAll(
                        new TomlParser().parse(tomlPaths, baseDir(), ctx)
                                .toList());
                logger.info("Parsed {} TOML files.", tomlPaths.size());
            } else {
                logger.info("No TOML files found to parse.");
            }

        } else {
            logger.info("Skipping parsing of YAML, Properties, XML, and TOML files as no resources were discovered or discovery was disabled.");
        }

        // Always attempt to parse Maven POM (typically pom.xml at baseDir)
        logger.info("Parsing Maven POMs (if found)...");
        try {
            Xml.Document pomAst = parseMaven(ctx); // parseMaven now returns null if POM not found/parsed
            if (pomAst != null) {
                sourceFiles.add(pomAst);
                logger.info("Parsed Maven POM: {}", pomAst.getSourcePath());
            } else {
                logger.info("No Maven POM found or parsed in {}", baseDir()); // Updated log
            }
        } catch (Exception e) {
            // Catch potential exceptions during POM parsing if it fails
            logger.warn("Failed to parse Maven POM. Skipping. Error: {}", e.getMessage(), e); // Log exception details
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
                })
                .toList();

        return new ResultsContainer(baseDir(), filteredResults);
    }

    // log method to mimic plugin behavior
    protected void log(LogLevel logLevel, CharSequence content) {
        switch (logLevel) {
            case DEBUG:
                logger.info(content.toString()); // Map DEBUG to INFO for now
                break;
            case INFO:
                logger.info(content.toString());
                break;
            case WARN:
                logger.warn(content.toString());
                break;
            case ERROR:
                logger.error(content.toString());
                break;
        }
    }

    // Updated Source URL
    // Source:
    // https://sourcegraph.com/github.com/openrewrite/rewrite-maven-plugin@v5.40.0/-/blob/src/main/java/org/openrewrite/maven/AbstractRewriteBaseRunMojo.java?L461-469
    protected void logRecipesThatMadeChanges(Result result) {
        String indent = "    ";
        // Use a fixed size for prefix to avoid string concatenation in a loop
        StringBuilder prefix = new StringBuilder("    ");
        for (RecipeDescriptor recipeDescriptor : result.getRecipeDescriptorsThatMadeChanges()) {
            logRecipe(recipeDescriptor, prefix.toString());
            prefix.append(indent);
        }
    }

    // Updated Source URL
    // Source:
    // https://sourcegraph.com/github.com/openrewrite/rewrite-maven-plugin@v5.40.0/-/blob/src/main/java/org/openrewrite/maven/AbstractRewriteBaseRunMojo.java?L471-489
    private void logRecipe(RecipeDescriptor rd, String prefix) {
        StringBuilder recipeString = new StringBuilder(prefix + rd.getName());
        if (!rd.getOptions().isEmpty()) {
            String opts = rd.getOptions().stream().map(option -> {
                if (option.getValue() != null) {
                    return option.getName() + "=" + option.getValue();
                }
                return null;
            }).filter(Objects::nonNull).collect(joining(", "));
            if (!opts.isEmpty()) {
                recipeString.append(": {").append(opts).append("}");
            }
        }
        // Use the new log method with configured level
        log(recipeChangeLogLevel, recipeString.toString());
        if (!rd.getRecipeList().isEmpty()) {
            for (RecipeDescriptor rchild : rd.getRecipeList()) {
                // Use StringBuilder to avoid string concatenation
                StringBuilder childPrefix = new StringBuilder(prefix);
                childPrefix.append("    ");
                logRecipe(rchild, childPrefix.toString());
            }
        }
    }

    void dryRun() {
        ResultsContainer results = listResults();

        if (results.isNotEmpty()) {
            for (Result result : results.generated) {
                assert result.getAfter() != null;
                logger.warn("These recipes would generate new file {}:", result.getAfter().getSourcePath());
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.deleted) {
                assert result.getBefore() != null;
                logger.warn("These recipes would delete file {}:", result.getBefore().getSourcePath());
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.moved) {
                assert result.getBefore() != null;
                assert result.getAfter() != null;
                logger.warn("These recipes would move file from {} to {}:",
                        result.getBefore().getSourcePath(), result.getAfter().getSourcePath());
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.refactoredInPlace) {
                assert result.getBefore() != null;
                logger.warn("These recipes would make changes to {}:", result.getBefore().getSourcePath());
                logRecipesThatMadeChanges(result);
            }

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
                                throw new RuntimeException(e);
                            }
                        });

            } catch (Exception e) {
                throw new IllegalStateException("Unable to generate rewrite result file.", e);
            }
            logger.warn("Report available:");
            logger.warn("    {}", patchFile.normalize());
            // logger.warn("Run 'mvn rewrite:run' to apply the recipes.");

            if (failOnDryRunResults) {
                throw new IllegalStateException("Applying recipes would make changes. See logs for more details.");
            }
        }
    }

    void realrun() {
        ResultsContainer results = listResults();

        if (results.isNotEmpty()) {
            for (Result result : results.generated) {
                assert result.getAfter() != null;
                logger.warn("Generated new file {} by:", 
                        result.getAfter().getSourcePath().normalize());
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.deleted) {
                assert result.getBefore() != null;
                logger.warn("Deleted file {} by:",
                        result.getBefore().getSourcePath().normalize());
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.moved) {
                assert result.getAfter() != null;
                assert result.getBefore() != null;
                logger.warn("File has been moved from {} to {} by:",
                        result.getBefore().getSourcePath().normalize(),
                        result.getAfter().getSourcePath().normalize());
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.refactoredInPlace) {
                assert result.getBefore() != null;
                logger.warn("Changes have been made to {} by:",
                        result.getBefore().getSourcePath().normalize());
                logRecipesThatMadeChanges(result);
            }

            logger.warn("Please review and commit the results.");

            try {
                for (Result result : results.generated) {
                    assert result.getAfter() != null;
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            results.getProjectRoot().resolve(result.getAfter().getSourcePath()))) {
                        Charset charset = result.getAfter().getCharset();
                        sourceFileWriter.write(new String(result.getAfter().printAll().getBytes(charset), charset));
                    }
                }
                for (Result result : results.deleted) {
                    assert result.getBefore() != null;
                    Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath())
                            .normalize();
                    boolean deleteSucceeded = originalLocation.toFile().delete();
                    if (!deleteSucceeded) {
                        throw new IOException(String.format("Unable to delete file %s", originalLocation.toAbsolutePath()));
                    }
                }
                for (Result result : results.moved) {
                    // Should we try to use git to move the file first, and only if that fails fall
                    // back to this?
                    assert result.getBefore() != null;
                    Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath());
                    boolean deleteSucceeded = originalLocation.toFile().delete();
                    if (!deleteSucceeded) {
                        throw new IOException(String.format("Unable to delete file %s", originalLocation.toAbsolutePath()));
                    }
                    assert result.getAfter() != null;
                    // Ensure directories exist in case something was moved into a hitherto
                    // non-existent package
                    Path afterLocation = results.getProjectRoot().resolve(result.getAfter().getSourcePath());
                    File parentDir = afterLocation.toFile().getParentFile();
                    
                    // Check return value of mkdirs()
                    if (!parentDir.exists() && !parentDir.mkdirs()) {
                        logger.warn("Failed to create directory: {}", parentDir);
                    }
                    
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(afterLocation)) {
                        Charset charset = result.getAfter().getCharset();
                        sourceFileWriter.write(new String(result.getAfter().printAll().getBytes(charset), charset));
                    }
                }
                for (Result result : results.refactoredInPlace) {
                    assert result.getBefore() != null;
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            results.getProjectRoot().resolve(result.getBefore().getSourcePath()))) {
                        assert result.getAfter() != null;
                        Charset charset = result.getAfter().getCharset();
                        sourceFileWriter.write(new String(result.getAfter().printAll().getBytes(charset), charset));
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to rewrite source files", e);
            }
        }
    }

    @Override
    public Integer call() { // your business logic goes here...

        if (dryRun) {
            dryRun();
        } else {
            realrun();
        }

        return 0;
    }

    public static RecipeDescriptor getRecipeDescriptor(String recipe, Collection<RecipeDescriptor> recipeDescriptors) {
        return recipeDescriptors.stream()
                .filter(r -> r.getName().equalsIgnoreCase(recipe))
                .findAny()
                .orElseThrow(() -> new IllegalStateException(String.format(RECIPE_NOT_FOUND_EXCEPTION_MSG, recipe)));
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
            String indent = StringUtils.repeat("    ", indentLevel * 4);
            if (currentRecursionLevel <= recursion) {
                if (verbose) {
                    logger.info("{}{}", indent, rd.getDisplayName());
                    logger.info("{}    {}", indent, rd.getName());
                    if (!rd.getDescription().isEmpty()) {
                        logger.info("{}    {}", indent, rd.getDescription());
                    }

                    if (!rd.getOptions().isEmpty()) {
                        logger.info("{}options: ", indent);
                        for (OptionDescriptor od : rd.getOptions()) {
                            logger.info("{}    {}: {}{}", 
                                indent, 
                                od.getName(), 
                                od.getType(),
                                od.isRequired() ? "!" : "");
                            if (od.getDescription() != null && !od.getDescription().isEmpty()) {
                                logger.info("{}        {}", indent, od.getDescription());
                            }
                        }
                    }
                } else {
                    logger.info("{}{}", indent, rd.getName());
                }

                if (!rd.getRecipeList().isEmpty() && (currentRecursionLevel + 1 <= recursion)) {
                    logger.info("{}recipeList:", indent);
                    for (RecipeDescriptor r : rd.getRecipeList()) {
                        writeRecipeDescriptor(r, verbose, currentRecursionLevel + 1, indentLevel + 1);
                    }
                }

                if (verbose) {
                    logger.info("");
                }
            }
        }

    }

}
