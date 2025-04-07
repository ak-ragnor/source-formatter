package com.codeformatter.plugins.react;

/**
 * Interface for React code analyzers.
 */
public interface ReactCodeAnalyzer {
    ReactAnalyzerResult analyze(JsAst ast);
    boolean canAutoFix();
    ReactRefactoringResult applyRefactoring(JsAst ast);
}