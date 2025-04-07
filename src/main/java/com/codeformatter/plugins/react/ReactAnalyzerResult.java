package com.codeformatter.plugins.react;

import com.codeformatter.api.error.FormatterError;

import java.util.List;

/**
 * Result of React code analysis.
 */
public class ReactAnalyzerResult {
    private final List<FormatterError> errors;

    public ReactAnalyzerResult(List<FormatterError> errors) {
        this.errors = errors;
    }

    public List<FormatterError> getErrors() {
        return errors;
    }
}
