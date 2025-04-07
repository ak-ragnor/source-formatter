package com.codeformatter.plugins.spring;

import java.util.List;

import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;

/**
 * Result of refactoring operations.
 */
public class RefactoringResult {
    private final List<Refactoring> appliedRefactorings;
    private final List<FormatterError> errors;

    public RefactoringResult(List<Refactoring> appliedRefactorings, List<FormatterError> errors) {
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
