import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.util.regex.Pattern;

/**
 * Utility class providing enhanced logging support for the Rewrite CLI
 */
public class LoggingUtils {
    // Constants for symbols
    public static final String SYMBOL_SUCCESS = "✓";
    public static final String SYMBOL_WARNING = "⚠";
    public static final String SYMBOL_ERROR = "✖";
    public static final String SYMBOL_INFO = "ℹ";
    public static final String SYMBOL_HEADING = "»";
    public static final String SYMBOL_BULLET = "•";
    public static final String SYMBOL_ARROW = "→";

    // Constants for style names
    public static final String STYLE_ERROR = "error";
    public static final String STYLE_WARNING = "warning";
    public static final String STYLE_SUCCESS = "success";
    public static final String STYLE_INFO = "info";
    public static final String STYLE_HEADING = "heading";
    public static final String STYLE_RECIPE = "recipe";
    public static final String STYLE_RECIPE_ACTIVE = "recipe-active";
    public static final String STYLE_FILENAME = "filename";
    public static final String STYLE_HIGHLIGHT = "highlight";

    // Pattern to detect the log lines we want to filter out
    private static final Pattern FILTER_PATTERN = Pattern.compile(
            "\\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*|.*\\[main].*|.*INFO\\s+Rewrite\\s*--.*");

    /**
     * Configure Logback programmatically, without using logback.xml
     */
    public static void configureLogbackProgrammatically() {
        // Completely disable SLF4J/Logback startup messages
        System.setProperty("logback.statusListenerClass", "ch.qos.logback.core.status.NopStatusListener");

        // Get the LoggerContext
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        // Reset any existing configuration
        context.reset();

        // Create a pattern encoder for normal output without timestamps or thread names
        PatternLayoutEncoder mainEncoder = new PatternLayoutEncoder();
        mainEncoder.setContext(context);
        mainEncoder.setPattern("%msg%n");
        mainEncoder.start();

        // Create our custom appender with colorization and filtering
        PicoCLIFilteringAppender mainAppender = new PicoCLIFilteringAppender();
        mainAppender.setContext(context);
        mainAppender.setEncoder(mainEncoder);
        mainAppender.start();

        // Explicitly handle specific loggers

        // Turn off all Logback's internal logging
        ch.qos.logback.classic.Logger logbackLogger = context.getLogger("ch.qos.logback");
        logbackLogger.setLevel(Level.OFF);

        // Suppress the specific troublesome logger
        ch.qos.logback.classic.Logger rewriteJavaLogger = context.getLogger("org.openrewrite.java.JavaParser");
        rewriteJavaLogger.setLevel(Level.OFF);

        ch.qos.logback.classic.Logger isolatedJavaLogger = context.getLogger("org.openrewrite.java.isolated");
        isolatedJavaLogger.setLevel(Level.OFF);

        // Set minimal level for all OpenRewrite logs
        ch.qos.logback.classic.Logger openrewriteLogger = context.getLogger("org.openrewrite");
        openrewriteLogger.setLevel(Level.WARN);

        // Configure root logger with our custom appender
        ch.qos.logback.classic.Logger rootLogger = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.INFO);
        rootLogger.addAppender(mainAppender);
    }

    /**
     * Custom Logback appender that colorizes and filters log messages
     */
    public static class PicoCLIFilteringAppender extends ConsoleAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            // Skip unwanted log messages that match our pattern
            if (shouldFilter(event)) {
                return;
            }

            // Format the message
            String formattedMessage = new String(encoder.encode(event));

            // Apply appropriate color based on log level
            String colorizedMessage = colorizeByLevel(event, formattedMessage);

            // Output to console
            System.out.print(colorizedMessage);
        }

        private boolean shouldFilter(ILoggingEvent event) {
            // Filter out empty messages
            if (event.getMessage() == null || event.getMessage().isEmpty()) {
                return true;
            }

            // Filter based on our pattern
            if (FILTER_PATTERN.matcher(event.getMessage()).matches()) {
                return true;
            }

            // Filter based on formatted message (with arguments)
            String formattedMessage = event.getFormattedMessage();
            return FILTER_PATTERN.matcher(formattedMessage).matches();
        }

        private String colorizeByLevel(ILoggingEvent event, String message) {
            String template;

            int level = event.getLevel().toInt();
            if (level == Level.ERROR_INT) {
                template = "@|bold,red %s|@";      // Bold red for errors only
            } else if (level == Level.WARN_INT) {
                template = "@|yellow %s|@";         // Yellow for warnings
            } else if (level == Level.INFO_INT) {
                template = "@|green %s|@";          // Green for info
            } else if (level == Level.DEBUG_INT) {
                template = "@|blue %s|@";           // Blue for debug
            } else {
                template = "%s";                    // Default - no color
            }

            return CommandLine.Help.Ansi.AUTO.string(String.format(template, message));
        }
    }

    /**
     * Enhanced console output with prefixes, symbols and structured indentation
     */
    public static void printColored(String message, String style, boolean noColor) {
        if (noColor) {
            System.out.println(message);
            return;
        }

        // Add prefix based on style
        String prefix = "";
        String symbol = "";

        if (STYLE_ERROR.equals(style)) {
            prefix = CommandLine.Help.Ansi.AUTO.string("@|bold,red ERROR|@ ");
            symbol = SYMBOL_ERROR + " ";
        } else if (STYLE_WARNING.equals(style)) {
            prefix = CommandLine.Help.Ansi.AUTO.string("@|yellow WARNING|@ ");
            symbol = SYMBOL_WARNING + " ";
        } else if (STYLE_SUCCESS.equals(style)) {
            prefix = CommandLine.Help.Ansi.AUTO.string("@|green SUCCESS|@ ");
            symbol = SYMBOL_SUCCESS + " ";
        } else if (STYLE_INFO.equals(style)) {
            prefix = CommandLine.Help.Ansi.AUTO.string("@|cyan INFO|@ ");
            symbol = SYMBOL_INFO + " ";
        } else if (STYLE_HEADING.equals(style)) {
            symbol = SYMBOL_HEADING + " ";
        } else if (STYLE_RECIPE.equals(style)) {
            symbol = SYMBOL_BULLET + " ";
        } else if (STYLE_RECIPE_ACTIVE.equals(style)) {
            prefix = CommandLine.Help.Ansi.AUTO.string("@|green ACTIVE|@ ");
            symbol = SYMBOL_ARROW + " ";
        }

        // Apply styling to the main message with the symbol
        String styledMessage = applyStyle(symbol + message, style);

        System.out.println(prefix + styledMessage);
    }

    /**
     * Format message with structured indentation and consistent styling
     */
    public static void printIndented(String message, String style, int indentLevel, boolean noColor) {
        // Create proper indentation
        String indent = " ".repeat(indentLevel * 2);
        printColored(indent + message, style, noColor);
    }

    /**
     * Format Java package/class names with colored dots
     * Example: "org.openrewrite.java" with purple dots between segments
     */
    public static String formatJavaName(String name, boolean noColor) {
        if (noColor) {
            return name;
        }

        // Don't try to format null or empty names
        if (name == null || name.isEmpty()) {
            return name;
        }

        // Split the package by dots to color each segment differently
        String[] parts = name.split("\\.");
        if (parts.length <= 1) {
            // Not a package name, return as is
            return name;
        }

        StringBuilder result = new StringBuilder();

        // Format each segment with appropriate color
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];

            // Add the package segment with appropriate styling
            if (i == 0) {
                // First part (usually "org") - white
                result.append(part);
            } else if (i == parts.length - 1) {
                // Last part (class name) - bolder
                result.append(CommandLine.Help.Ansi.AUTO.string("@|bold " + part + "|@"));
            } else if (i == 2) {
                // The category (e.g., "java", "maven", "yaml") - cyan with emphasis
                result.append(CommandLine.Help.Ansi.AUTO.string("@|cyan " + part + "|@"));
            } else {
                // Other parts - normal text
                result.append(part);
            }

            // Add colored dot if not the last segment
            if (i < parts.length - 1) {
                // Add the magenta dot - this needs to be a separate color 
                // operation to avoid being affected by surrounding styling
                result.append(CommandLine.Help.Ansi.AUTO.string("@|magenta,bold .|@"));
            }
        }

        return result.toString();
    }

    /**
     * Apply a style to text using PicoCLI's color scheme
     */
    public static String applyStyle(String text, String style) {
        String template;

        switch (style) {
            case STYLE_ERROR -> template = "@|bold,red %s|@";
            case STYLE_WARNING -> template = "@|yellow %s|@";
            case STYLE_SUCCESS -> template = "@|green %s|@";
            case STYLE_FILENAME -> template = "@|cyan %s|@";
            case STYLE_HEADING -> template = "@|bold %s|@";
            case STYLE_RECIPE -> template = "@|blue %s|@";
            case STYLE_RECIPE_ACTIVE -> template = "@|green,bold %s|@";
            case STYLE_HIGHLIGHT -> template = "@|bold,yellow %s|@";
            case null, default -> {
                return text; // No styling
            }
        }

        return CommandLine.Help.Ansi.AUTO.string(String.format(template, text));
    }
}
