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
import java.util.stream.Collectors;

/**
 * Analyzes and organizes import statements in React/JavaScript files
 */
public class ImportOrganizer implements ReactCodeAnalyzer {
    private final FormatterConfig config;
    private final JsEngine jsEngine;
    private final List<String> importGroups;

    @SuppressWarnings("unchecked")
    public ImportOrganizer(FormatterConfig config, JsEngine jsEngine) {
        this.config = config;
        this.jsEngine = jsEngine;

        // Get configured import groups or use default
        List<String> configuredGroups = (List<String>) config.getPluginConfig(
                "react",
                "importOrganization.groups",
                Arrays.asList("react", "external", "internal", "css")
        );

        this.importGroups = configuredGroups;
    }

    @Override
    public ReactAnalyzerResult analyze(JsAst ast) {
        if (!ast.isValid()) {
            return new ReactAnalyzerResult(Collections.emptyList());
        }

        List<FormatterError> errors = new ArrayList<>();

        // Find all import declarations
        Value[] importNodes = ast.findNodes("ImportDeclaration");

        if (importNodes.length == 0) {
            return new ReactAnalyzerResult(errors);
        }

        // Group imports by type
        Map<String, List<Value>> groupedImports = groupImports(importNodes, ast);

        // Check if imports are already organized
        if (!areImportsOrganized(importNodes, groupedImports)) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Import statements are not organized according to convention",
                    ast.getNodeLine(importNodes[0]),
                    ast.getNodeColumn(importNodes[0]),
                    "Organize imports by group: " + String.join(", ", importGroups)
            ));
        }

        // Check for duplicate imports
        checkDuplicateImports(importNodes, errors, ast);

        // Check for unused imports (would require more complex analysis)

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

        // Find all import declarations
        Value[] importNodes = ast.findNodes("ImportDeclaration");

        if (importNodes.length == 0) {
            return new ReactRefactoringResult(refactorings, errors);
        }

        // Apply the import organization transformation
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
    private Map<String, List<Value>> groupImports(Value[] importNodes, JsAst ast) {
        Map<String, List<Value>> groupedImports = new HashMap<>();

        for (String group : importGroups) {
            groupedImports.put(group, new ArrayList<>());
        }

        for (Value node : importNodes) {
            String source = ast.getStringProperty(node.getMember("source"), "value");
            String group = determineImportGroup(source);
            groupedImports.get(group).add(node);
        }

        return groupedImports;
    }

    /**
     * Determine which group an import belongs to
     */
    private String determineImportGroup(String importPath) {
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
    private boolean areImportsOrganized(Value[] importNodes, Map<String, List<Value>> groupedImports) {
        int currentIndex = 0;

        // Check if imports appear in the expected order
        for (String group : importGroups) {
            List<Value> imports = groupedImports.get(group);

            if (imports.isEmpty()) {
                continue;
            }

            // Check if all imports in this group are consecutive
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
    private void checkDuplicateImports(Value[] importNodes, List<FormatterError> errors, JsAst ast) {
        Map<String, List<Value>> importsBySource = new HashMap<>();

        // Group imports by source
        for (Value node : importNodes) {
            String source = ast.getStringProperty(node.getMember("source"), "value");
            importsBySource.computeIfAbsent(source, k -> new ArrayList<>()).add(node);
        }

        // Check for duplicates
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