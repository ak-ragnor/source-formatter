package com.codeformatter.plugins.react;

import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;

import java.util.List;

/**
 * Result of React code refactoring.
 */
public class ReactRefactoringResult {
    private final List<Refactoring> appliedRefactorings;
    private final List<FormatterError> errors;

    public ReactRefactoringResult(List<Refactoring> appliedRefactorings, List<FormatterError> errors) {
        this.appliedRefactorings = appliedRefactorings;
        this.errors = errors;
    }

    public List<Refactoring> getAppliedRefactorings() {
        return appliedRefactorings;
    }

    public List<FormatterError> getErrors() {
        return errors;
    }
}