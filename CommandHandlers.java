import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.config.OptionDescriptor;
import org.openrewrite.style.NamedStyles;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Command handlers for the rewrite CLI
 */
public class CommandHandlers {

    /**
     * Command that discovers available recipes and styles
     */
    @Command(name = "discover", mixinStandardHelpOptions = true,
            description = "List all available recipes and styles.", 
            headerHeading = "%n",
            header = "Discover available OpenRewrite recipes and styles")
    public static class RewriteDiscover implements Callable<Integer> {

        @ParentCommand
        private Rewrite rewrite; // picocli injects reference to parent command

        /**
         * The name of a specific recipe to show details for. For example:<br>
         * {@code rewrite discover --detail --recipe=org.openrewrite.java.format.AutoFormat}
         */
        @Option(names = "recipe")
        String recipe;

        /**
         * Filter recipes by type/category (matches against package name). For example:<br>
         * {@code rewrite discover --type=java} will show only Java recipes.
         */
        @Option(names = {"--type", "--category"}, description = "Filter recipes by category (java, maven, yaml, etc.)")
        String typeFilter;

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
            
            // Apply type/category filter if specified
            if (typeFilter != null && !typeFilter.isEmpty()) {
                final String filter = typeFilter.toLowerCase();
                availableRecipeDescriptors = availableRecipeDescriptors.stream()
                    .filter(rd -> categoryMatches(rd.getName(), filter))
                    .collect(java.util.stream.Collectors.toList());
                    
                System.out.println("\nFiltering by category: " + LoggingUtils.formatJavaName("org.openrewrite." + filter, rewrite.noColor));
            }
            
            if (recipe != null) {
                RecipeDescriptor rd = rewrite.getRecipeDescriptor(recipe, availableRecipeDescriptors);
                writeRecipeDescriptor(rd, detail, 0, 0);
            } else {
                Collection<RecipeDescriptor> activeRecipeDescriptors = new HashSet<>();
                for (String activeRecipe : rewrite.activeRecipes) {
                    RecipeDescriptor rd = rewrite.getRecipeDescriptor(activeRecipe, availableRecipeDescriptors);
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
} 