package com.codeformatter.plugins.spring;

import com.codeformatter.api.error.FormatterError;

import java.util.List;


/**
 * Result of code analysis.
 */
public class AnalyzerResult {
    private final List<FormatterError> errors;
    
    public AnalyzerResult(List<FormatterError> errors) {
        this.errors = errors;
    }
    
    public List<FormatterError> getErrors() {
        return errors;
    }
}
