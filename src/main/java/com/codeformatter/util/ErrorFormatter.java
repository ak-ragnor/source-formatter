package com.codeformatter.util;

import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility for formatting error messages consistently.
 */
public class ErrorFormatter {
    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_YELLOW = "\u001B[33m";
    public static final String ANSI_BLUE = "\u001B[34m";
    public static final String ANSI_BOLD = "\u001B[1m";

    private final boolean useColors;

    /**
     * Creates a new error formatter.
     *
     * @param useColors whether to use colors in the output
     */
    public ErrorFormatter(boolean useColors) {
        this.useColors = useColors;
    }

    /**
     * Formats a formatter error message.
     */
    public String formatError(FormatterError error) {
        StringBuilder sb = new StringBuilder();

        String severityStr = switch (error.getSeverity()) {
            case FATAL -> colorize(ANSI_RED, "FATAL");
            case ERROR -> colorize(ANSI_RED, "ERROR");
            case WARNING -> colorize(ANSI_YELLOW, "WARNING");
            case INFO -> colorize(ANSI_BLUE, "INFO");
        };

        sb.append(severityStr).append(": ");
        sb.append(error.getMessage());
        sb.append(" (Line ").append(error.getLine()).append(")");

        if (error.getSuggestion() != null && !error.getSuggestion().isEmpty()) {
            sb.append("\n  ").append(colorize(ANSI_GREEN, "Suggestion: "))
                    .append(error.getSuggestion());
        }

        return sb.toString();
    }

    /**
     * Creates a summary of errors per file.
     */
    public String formatErrorSummary(Map<Path, List<FormatterError>> fileErrors) {
        StringBuilder sb = new StringBuilder();

        sb.append(colorize(ANSI_BOLD, "Error Summary:\n"));

        int totalErrors = 0;
        int totalWarnings = 0;
        int totalInfos = 0;
        int totalFatals = 0;

        for (Map.Entry<Path, List<FormatterError>> entry : fileErrors.entrySet()) {
            Path file = entry.getKey();
            List<FormatterError> errors = entry.getValue();

            if (errors.isEmpty()) {
                continue;
            }

            // Count errors by severity
            long fatals = errors.stream().filter(e -> e.getSeverity() == Severity.FATAL).count();
            long errs = errors.stream().filter(e -> e.getSeverity() == Severity.ERROR).count();
            long warnings = errors.stream().filter(e -> e.getSeverity() == Severity.WARNING).count();
            long infos = errors.stream().filter(e -> e.getSeverity() == Severity.INFO).count();

            totalFatals += fatals;
            totalErrors += errs;
            totalWarnings += warnings;
            totalInfos += infos;

            sb.append(file.getFileName()).append(": ");

            if (fatals > 0) {
                sb.append(colorize(ANSI_RED, fatals + " fatal")).append(", ");
            }
            if (errs > 0) {
                sb.append(colorize(ANSI_RED, errs + " errors")).append(", ");
            }
            if (warnings > 0) {
                sb.append(colorize(ANSI_YELLOW, warnings + " warnings")).append(", ");
            }
            if (infos > 0) {
                sb.append(colorize(ANSI_BLUE, infos + " info")).append(", ");
            }

            // Remove trailing comma and space
            if (sb.charAt(sb.length() - 2) == ',') {
                sb.delete(sb.length() - 2, sb.length());
            }

            sb.append("\n");
        }

        sb.append("\nTotal: ");
        if (totalFatals > 0) {
            sb.append(colorize(ANSI_RED, totalFatals + " fatal")).append(", ");
        }
        if (totalErrors > 0) {
            sb.append(colorize(ANSI_RED, totalErrors + " errors")).append(", ");
        }
        if (totalWarnings > 0) {
            sb.append(colorize(ANSI_YELLOW, totalWarnings + " warnings")).append(", ");
        }
        if (totalInfos > 0) {
            sb.append(colorize(ANSI_BLUE, totalInfos + " info"));
        }

        return sb.toString();
    }

    /**
     * Groups errors by severity.
     */
    public Map<Severity, List<FormatterError>> groupBySeverity(List<FormatterError> errors) {
        return errors.stream().collect(Collectors.groupingBy(FormatterError::getSeverity));
    }

    /**
     * Applies ANSI color to text if colors are enabled.
     */
    public String colorize(String color, String message) {
        if (useColors) {
            return color + message + ANSI_RESET;
        }
        return message;
    }
}