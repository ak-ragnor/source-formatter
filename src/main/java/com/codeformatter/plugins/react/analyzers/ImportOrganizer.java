package com.codeformatter.plugins.react.analyzers;

import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.react.JsAst;
import com.codeformatter.plugins.react.JsEngine;
import com.codeformatter.plugins.react.ReactAnalyzerResult;
import com.codeformatter.plugins.react.ReactCodeAnalyzer;
import com.codeformatter.plugins.react.ReactRefactoringResult;
import org.graalvm.polyglot.Value;

import java.util.*;

/**
 * Analyzes and organizes import statements in React/JavaScript files
 */
public class ImportOrganizer implements ReactCodeAnalyzer {
    private final FormatterConfig config;
    private final JsEngine jsEngine;
    private final List<String> importGroups;

    public ImportOrganizer(FormatterConfig config, JsEngine jsEngine) {
        this.config = config;
        this.jsEngine = jsEngine;
        this.importGroups = config.getPluginConfig(
                "react",
                "importOrganization.groups",
                Arrays.asList("react", "external", "internal", "css")
        );
    }

    @Override
    public ReactAnalyzerResult analyze(JsAst ast) {
        if (!ast.isValid()) {
            return new ReactAnalyzerResult(Collections.emptyList());
        }

        Value[] importNodes = ast.findNodes("ImportDeclaration");

        List<FormatterError> errors = new ArrayList<>();

        if (importNodes.length == 0) {
            return new ReactAnalyzerResult(errors);
        }

        Map<String, List<Value>> groupedImports = _groupImports(importNodes, ast);

        if (!_areImportsOrganized(importNodes, groupedImports)) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Import statements are not organized according to convention",
                    ast.getNodeLine(importNodes[0]),
                    ast.getNodeColumn(importNodes[0]),
                    "Organize imports by group: " + String.join(", ", importGroups)
            ));
        }

        _checkDuplicateImports(importNodes, errors, ast);

        return new ReactAnalyzerResult(errors);
    }

    @Override
    public boolean canAutoFix() {
        return true;
    }

    @Override
    public ReactRefactoringResult applyRefactoring(JsAst ast) {
        if (!ast.isValid()) {
            return new ReactRefactoringResult(Collections.emptyList(), Collections.emptyList());
        }

        List<Refactoring> refactorings = new ArrayList<>();
        List<FormatterError> errors = new ArrayList<>();

        Value[] importNodes = ast.findNodes("ImportDeclaration");

        if (importNodes.length == 0) {
            return new ReactRefactoringResult(refactorings, errors);
        }

        Map<String, Object> options = new HashMap<>();
        options.put("groups", importGroups);

        boolean success = jsEngine.transformAst(ast, "organizeImports", options);

        if (success) {
            refactorings.add(new Refactoring(
                    "IMPORT_ORGANIZATION",
                    ast.getNodeLine(importNodes[0]),
                    ast.getNodeLine(importNodes[importNodes.length - 1]),
                    "Organized imports according to convention"
            ));
        } else {
            errors.add(new FormatterError(
                    Severity.ERROR,
                    "Failed to organize imports",
                    ast.getNodeLine(importNodes[0]),
                    ast.getNodeColumn(importNodes[0])
            ));
        }

        return new ReactRefactoringResult(refactorings, errors);
    }

    /**
     * Group import nodes by their type (react, external, internal, css)
     */
    private Map<String, List<Value>> _groupImports(Value[] importNodes, JsAst ast) {
        Map<String, List<Value>> groupedImports = new HashMap<>();

        for (String group : importGroups) {
            groupedImports.put(group, new ArrayList<>());
        }

        for (Value node : importNodes) {
            String source = ast.getStringProperty(node.getMember("source"), "value");
            String group = _determineImportGroup(source);
            groupedImports.get(group).add(node);
        }

        return groupedImports;
    }

    /**
     * Determine which group an import belongs to
     */
    private String _determineImportGroup(String importPath) {
        if (importPath.equals("react") || importPath.startsWith("react-")) {
            return "react";
        } else if (importPath.startsWith("./") || importPath.startsWith("../") || importPath.startsWith("/")) {
            return "internal";
        } else if (importPath.endsWith(".css") || importPath.endsWith(".scss") || importPath.endsWith(".less")) {
            return "css";
        } else {
            return "external";
        }
    }

    /**
     * Check if imports are already organized according to our convention
     */
    private boolean _areImportsOrganized(Value[] importNodes, Map<String, List<Value>> groupedImports) {
        int currentIndex = 0;

        for (String group : importGroups) {
            List<Value> imports = groupedImports.get(group);

            if (imports.isEmpty()) {
                continue;
            }

            for (Value importNode : imports) {
                if (Arrays.asList(importNodes).indexOf(importNode) != currentIndex) {
                    return false;
                }
                currentIndex++;
            }
        }

        return true;
    }

    /**
     * Check for duplicate imports
     */
    private void _checkDuplicateImports(Value[] importNodes, List<FormatterError> errors, JsAst ast) {
        Map<String, List<Value>> importsBySource = new HashMap<>();

        for (Value node : importNodes) {
            String source = ast.getStringProperty(node.getMember("source"), "value");
            importsBySource.computeIfAbsent(source, k -> new ArrayList<>()).add(node);
        }

        for (Map.Entry<String, List<Value>> entry : importsBySource.entrySet()) {
            if (entry.getValue().size() > 1) {
                Value firstNode = entry.getValue().get(0);
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "Duplicate import for '" + entry.getKey() + "'",
                        ast.getNodeLine(firstNode),
                        ast.getNodeColumn(firstNode),
                        "Merge duplicate imports"
                ));
            }
        }
    }
}