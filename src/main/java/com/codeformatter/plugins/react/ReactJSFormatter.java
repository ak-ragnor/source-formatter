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
import java.util.List;

/**
 * React JS formatter plugin using Babel/TypeScript parser to analyze and refactor React code.
 * This leverages a JavaScript engine bridge (GraalJS, Nashorn, etc.) to use JavaScript-based
 * parsers for accurate React code analysis.
 */
public class ReactJSFormatter implements FormatterPlugin {

    private FormatterConfig config;
    private List<ReactCodeAnalyzer> analyzers;
    private JsEngine jsEngine;

    @Override
    public void initialize(FormatterConfig config) {
        this.config = config;
        this.jsEngine = new JsEngine(); // Initialize JS engine bridge

        // Initialize analyzers
        analyzers = new ArrayList<>();
        analyzers.add(new ComponentStructureAnalyzer(config, jsEngine));
        analyzers.add(new HookUsageAnalyzer(config, jsEngine));
        analyzers.add(new StateManagementAnalyzer(config, jsEngine));
        analyzers.add(new JsxStyleAnalyzer(config, jsEngine));
        analyzers.add(new ImportOrganizer(config, jsEngine));
    }

    @Override
    public FormatterResult format(Path filePath, String sourceCode) {
        // Initialize parser and parse the code
        JsAst ast = jsEngine.parseReactCode(sourceCode, isTypeScript(filePath));

        if (!ast.isValid()) {
            return handleParseError(ast.getError());
        }

        List<FormatterError> errors = new ArrayList<>();
        List<Refactoring> appliedRefactorings = new ArrayList<>();

        // Apply all analyzers
        for (ReactCodeAnalyzer analyzer : analyzers) {
            ReactAnalyzerResult analyzerResult = analyzer.analyze(ast);
            errors.addAll(analyzerResult.getErrors());

            // Apply automatic refactorings if possible
            if (analyzer.canAutoFix()) {
                ReactRefactoringResult refactoringResult = analyzer.applyRefactoring(ast);
                appliedRefactorings.addAll(refactoringResult.getAppliedRefactorings());
                errors.addAll(refactoringResult.getErrors());
            }
        }

        // Generate final formatted code
        String formattedCode = jsEngine.generateCode(ast);

        boolean successful = !errors.stream()
                .anyMatch(e -> e.getSeverity() == Severity.FATAL || e.getSeverity() == Severity.ERROR);

        return FormatterResult.builder()
                .successful(successful)
                .formattedCode(formattedCode)
                .errors(errors)
                .appliedRefactorings(appliedRefactorings)
                .build();
    }

    private boolean isTypeScript(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        return fileName.endsWith(".ts") || fileName.endsWith(".tsx");
    }

    private FormatterResult handleParseError(String errorMessage) {
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
}