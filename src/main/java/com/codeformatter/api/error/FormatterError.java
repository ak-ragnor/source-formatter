package com.codeformatter.api.error;

/**
 * Represents an error found during formatting.
 */
public class FormatterError {
    private final Severity severity;
    private final String message;
    private final int line;
    private final String suggestion;

    public FormatterError(Severity severity, String message, int line, int column) {
        this(severity, message, line, column, null);
    }

    public FormatterError(Severity severity, String message, int line, int column, String suggestion) {
        this.severity = severity;
        this.message = message;
        this.line = line;
        this.suggestion = suggestion;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public int getLine() {
        return line;
    }

    public String getSuggestion() {
        return suggestion;
    }
}