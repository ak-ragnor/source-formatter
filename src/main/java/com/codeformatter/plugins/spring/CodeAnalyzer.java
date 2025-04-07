package com.codeformatter.plugins.spring;

import com.github.javaparser.ast.CompilationUnit;

/**
 * Interface for code analyzers that can find issues and suggest refactorings.
 */
public interface CodeAnalyzer {
    AnalyzerResult analyze(CompilationUnit cu);
    boolean canAutoFix();
    RefactoringResult applyRefactoring(CompilationUnit cu);
}
