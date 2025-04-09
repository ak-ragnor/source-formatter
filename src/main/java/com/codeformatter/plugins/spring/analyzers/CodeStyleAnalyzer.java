package com.codeformatter.plugins.spring.analyzers;

import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.CodeAnalyzer;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.github.javaparser.ast.CompilationUnit;

import java.util.ArrayList;

public class CodeStyleAnalyzer implements CodeAnalyzer {
    public CodeStyleAnalyzer(FormatterConfig config) {
    }
    
    @Override
    public AnalyzerResult analyze(CompilationUnit cu) {
        return new AnalyzerResult(new ArrayList<>());
    }
    
    @Override
    public boolean canAutoFix() {
        return true;
    }
    
    @Override
    public RefactoringResult applyRefactoring(CompilationUnit cu) {
        return new RefactoringResult(new ArrayList<>(), new ArrayList<>());
    }
}
            