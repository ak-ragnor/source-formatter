package com.codeformatter.api;

import java.util.ArrayList;
import java.util.List;

import com.codeformatter.api.error.FormatterError;

/**
 * Result of a formatting operation.
 */
public class FormatterResult {
    private final boolean successful;
    private final String formattedCode;
    private final List<FormatterError> errors;
    private final List<Refactoring> appliedRefactorings;
    
    private FormatterResult(Builder builder) {
        this.successful = builder.successful;
        this.formattedCode = builder.formattedCode;
        this.errors = builder.errors;
        this.appliedRefactorings = builder.appliedRefactorings;
    }
    
    public boolean isSuccessful() {
        return successful;
    }
    
    public String getFormattedCode() {
        return formattedCode;
    }
    
    public List<FormatterError> getErrors() {
        return errors;
    }
    
    public List<Refactoring> getAppliedRefactorings() {
        return appliedRefactorings;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private boolean successful;
        private String formattedCode;
        private List<FormatterError> errors = new ArrayList<>();
        private List<Refactoring> appliedRefactorings = new ArrayList<>();
        
        public Builder successful(boolean successful) {
            this.successful = successful;
            return this;
        }
        
        public Builder formattedCode(String formattedCode) {
            this.formattedCode = formattedCode;
            return this;
        }
        
        public Builder addError(FormatterError error) {
            this.errors.add(error);
            return this;
        }
        
        public Builder addRefactoring(Refactoring refactoring) {
            this.appliedRefactorings.add(refactoring);
            return this;
        }
        
        public FormatterResult build() {
            return new FormatterResult(this);
        }
    }
}
