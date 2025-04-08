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
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Spring Boot code formatter plugin with caching and resource management.
 * This plugin uses JavaParser to analyze and refactor Java code
 * with awareness of Spring Boot specific patterns.
 */
public class SpringBootFormatter implements FormatterPlugin, AutoCloseable {

    private FormatterConfig config;
    private List<CodeAnalyzer> analyzers;

    private final Map<String, CompilationUnit> astCache = new LinkedHashMap<String, CompilationUnit>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CompilationUnit> eldest) {
            return size() > 100;
        }
    };

    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = cacheLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = cacheLock.writeLock();

    @Override
    public void initialize(FormatterConfig config) {
        this.config = config;

        analyzers = new ArrayList<>();
        analyzers.add(new MethodSizeAnalyzer(config));
        analyzers.add(new ImportOrganizer(config));
        analyzers.add(new DesignPatternAnalyzer(config));
        analyzers.add(new SpringComponentAnalyzer(config));
        analyzers.add(new CodeStyleAnalyzer(config));
    }

    @Override
    public FormatterResult format(Path filePath, String sourceCode) {
        // Check cache first using a composite key of file path and content hashcode
        String cacheKey = filePath.toString() + ":" + sourceCode.hashCode();
        CompilationUnit cu = null;

        // Try to get from cache using read lock
        readLock.lock();
        try {
            cu = astCache.get(cacheKey);
        } finally {
            readLock.unlock();
        }

        ParseResult<CompilationUnit> parseResult = null;

        // If not in cache, parse it
        if (cu == null) {
            JavaParser parser = new JavaParser();
            parseResult = parser.parse(sourceCode);

            if (!parseResult.isSuccessful()) {
                return handleParseError(parseResult);
            }

            cu = parseResult.getResult().get();
            LexicalPreservingPrinter.setup(cu);

            // Store in cache using write lock
            writeLock.lock();
            try {
                astCache.put(cacheKey, cu);
            } finally {
                writeLock.unlock();
            }
        }

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

        boolean successful = errors.stream()
                .noneMatch(e -> e.getSeverity() == Severity.FATAL || e.getSeverity() == Severity.ERROR);

        // Use the new errors() method to set the entire list
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
                "Failed to parse Java source code: " +
                        (parseResult.getProblems().isEmpty() ? "Unknown error" :
                                parseResult.getProblems().get(0).getMessage()),
                1, 1);

        // Use builder method to add a single error
        return FormatterResult.builder()
                .successful(false)
                .formattedCode(null)
                .addError(error)
                .build();
    }

    /**
     * Cleans up resources when the formatter is no longer needed.
     */
    @Override
    public void close() {
        writeLock.lock();
        try {
            astCache.clear();
        } finally {
            writeLock.unlock();
        }
    }
}