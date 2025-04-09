package com.codeformatter.plugins.react;

import com.codeformatter.api.FormatterPlugin;
import com.codeformatter.api.FormatterResult;
import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.react.analyzers.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * React JS formatter plugin using Babel/TypeScript parser to analyze and refactor React code.
 * This leverages a JavaScript engine bridge (GraalJS, Nashorn, etc.) to use JavaScript-based
 * parsers for accurate React code analysis.
 */
public class ReactJSFormatter implements FormatterPlugin, AutoCloseable {

    private FormatterConfig config;
    private List<ReactCodeAnalyzer> analyzers;
    private JsEngine jsEngine;

    private final Map<String, JsAst> astCache = new LinkedHashMap<String, JsAst>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JsAst> eldest) {
            return size() > 100;
        }
    };

    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = cacheLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = cacheLock.writeLock();

    @Override
    public void initialize(FormatterConfig config) {
        this.config = config;
        this.jsEngine = new JsEngine();

        analyzers = new ArrayList<>();
        analyzers.add(new ComponentStructureAnalyzer(config, jsEngine));
        analyzers.add(new HookUsageAnalyzer(config, jsEngine));
        analyzers.add(new StateManagementAnalyzer(config, jsEngine));
        analyzers.add(new JsxStyleAnalyzer(config, jsEngine));
        analyzers.add(new ImportOrganizer(config, jsEngine));
    }

    @Override
    public FormatterResult format(Path filePath, String sourceCode) {
        String cacheKey = filePath.toString() + ":" + sourceCode.hashCode();
        JsAst ast = null;

        readLock.lock();
        try {
            ast = astCache.get(cacheKey);
        } finally {
            readLock.unlock();
        }

        if (ast == null) {
            ast = jsEngine.parseReactCode(sourceCode, _isTypeScript(filePath));

            if (ast.isValid()) {
                writeLock.lock();
                try {
                    astCache.put(cacheKey, ast);
                } finally {
                    writeLock.unlock();
                }
            }
        }

        if (!ast.isValid()) {
            return _handleParseError(ast.getError());
        }

        List<FormatterError> errors = new ArrayList<>();
        List<Refactoring> appliedRefactorings = new ArrayList<>();

        for (ReactCodeAnalyzer analyzer : analyzers) {
            ReactAnalyzerResult analyzerResult = analyzer.analyze(ast);
            errors.addAll(analyzerResult.getErrors());

            if (analyzer.canAutoFix()) {
                ReactRefactoringResult refactoringResult = analyzer.applyRefactoring(ast);
                appliedRefactorings.addAll(refactoringResult.getAppliedRefactorings());
                errors.addAll(refactoringResult.getErrors());
            }
        }

        String formattedCode = jsEngine.generateCode(ast);

        boolean successful = errors.stream()
                .noneMatch(e -> e.getSeverity() == Severity.FATAL || e.getSeverity() == Severity.ERROR);

        return FormatterResult.builder()
                .successful(successful)
                .formattedCode(formattedCode)
                .errors(errors)
                .appliedRefactorings(appliedRefactorings)
                .build();
    }

    private boolean _isTypeScript(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        return fileName.endsWith(".ts") || fileName.endsWith(".tsx");
    }

    private FormatterResult _handleParseError(String errorMessage) {
        FormatterError error = new FormatterError(
                Severity.FATAL,
                "Failed to parse React source code: " + errorMessage,
                1, 1);

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
        if (jsEngine != null) {
            jsEngine.close();
        }

        writeLock.lock();
        try {
            astCache.clear();
        } finally {
            writeLock.unlock();
        }
    }
}