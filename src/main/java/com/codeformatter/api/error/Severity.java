package com.codeformatter.api.error;

public enum Severity {
    FATAL,   // Syntax errors or other issues preventing formatting
    ERROR,   // Issues requiring manual intervention
    WARNING, // Code smells or non-critical issues
    INFO     // Informational messages about formatting
}
