package com.codeformatter.plugins.spring;

import com.codeformatter.api.FormatterPlugin;
import com.codeformatter.api.FormatterResult;
import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.analyzers.*;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot code formatter plugin.
 * This plugin uses JavaParser to analyze and refactor Java code
 * with awareness of Spring Boot specific patterns.
 */
public class SpringBootFormatter implements FormatterPlugin {

    private FormatterConfig config;
    private List<CodeAnalyzer> analyzers;
    
    @Override
    public void initialize(FormatterConfig config) {
        this.config = config;
        
        // Initialize the code analyzers
        analyzers = new ArrayList<>();
        analyzers.add(new MethodSizeAnalyzer(config));
        analyzers.add(new ImportOrganizer(config));
        analyzers.add(new DesignPatternAnalyzer(config));
        analyzers.add(new SpringComponentAnalyzer(config));
        analyzers.add(new CodeStyleAnalyzer(config));
    }

    @Override
    public FormatterResult format(Path filePath, String sourceCode) {
        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> parseResult = parser.parse(sourceCode);
        
        if (!parseResult.isSuccessful()) {
            return handleParseError(parseResult);
        }
        
        CompilationUnit cu = parseResult.getResult().get();
        LexicalPreservingPrinter.setup(cu);
        
        List<FormatterError> errors = new ArrayList<>();
        List<Refactoring> appliedRefactorings = new ArrayList<>();
        
        // Apply all analyzers to find issues
        for (CodeAnalyzer analyzer : analyzers) {
            AnalyzerResult analyzerResult = analyzer.analyze(cu);
            errors.addAll(analyzerResult.getErrors());
            
            // Apply automatic refactorings
            if (analyzer.canAutoFix()) {
                RefactoringResult refactoringResult = analyzer.applyRefactoring(cu);
                appliedRefactorings.addAll(refactoringResult.getAppliedRefactorings());
                errors.addAll(refactoringResult.getErrors());
            }
        }
        
        // Generate the final formatted code
        String formattedCode = LexicalPreservingPrinter.print(cu);
        
        boolean successful = !errors.stream()
                .anyMatch(e -> e.getSeverity() == Severity.FATAL || e.getSeverity() == Severity.ERROR);
        
        return FormatterResult.builder()
                .successful(successful)
                .formattedCode(formattedCode)
                .errors(errors)
                .appliedRefactorings(appliedRefactorings)
                .build();
    }
    
    private FormatterResult handleParseError(ParseResult<CompilationUnit> parseResult) {
        FormatterError error = new FormatterError(
                Severity.FATAL,
                "Failed to parse Java source code: " + parseResult.getProblems().get(0).getMessage(),
                1, 1);
        
        return FormatterResult.builder()
                .successful(false)
                .formattedCode(null)
                .addError(error)
                .build();
    }
}