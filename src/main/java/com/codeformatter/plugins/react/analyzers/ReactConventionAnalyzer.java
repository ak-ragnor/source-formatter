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
import com.codeformatter.util.LoggerUtil;
import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Analyzes React code for adherence to React coding conventions.
 */
public class ReactConventionAnalyzer implements ReactCodeAnalyzer {
    private static final Logger logger = LoggerUtil.getLogger(ReactConventionAnalyzer.class.getName());

    private final FormatterConfig config;
    private final JsEngine jsEngine;

    private static final List<String> IMPORT_TYPE_ORDER = Arrays.asList(
            "module", "named", "default", "side-effect"
    );

    public ReactConventionAnalyzer(FormatterConfig config, JsEngine jsEngine) {
        this.config = config;
        this.jsEngine = jsEngine;
    }

    @Override
    public ReactAnalyzerResult analyze(JsAst ast) {
        if (!ast.isValid()) {
            return new ReactAnalyzerResult(new ArrayList<>());
        }

        List<FormatterError> errors = new ArrayList<>();

        _checkImportConventions(ast, errors);
        _checkExportConventions(ast, errors);
        _checkTypeAssertionConventions(ast, errors);
        _checkSwitchStatementConventions(ast, errors);

        return new ReactAnalyzerResult(errors);
    }

    @Override
    public boolean canAutoFix() {
        return false;
    }

    @Override
    public ReactRefactoringResult applyRefactoring(JsAst ast) {
        return new ReactRefactoringResult(new ArrayList<>(), new ArrayList<>());
    }

    /**
     * Check import conventions:
     * - Import order: module, named, default, side effect
     * - Use relative imports for files within same project
     */
    private void _checkImportConventions(JsAst ast, List<FormatterError> errors) {
        Value[] imports = ast.findNodes("ImportDeclaration");

        Map<String, List<Value>> importsByType = new HashMap<>();
        for (String type : IMPORT_TYPE_ORDER) {
            importsByType.put(type, new ArrayList<>());
        }

        for (Value importNode : imports) {
            if (importNode.hasMember("source")) {
                String source = ast.getStringProperty(importNode.getMember("source"), "value");

                if (!source.startsWith(".") && !source.startsWith("/") &&
                        source.startsWith("@") && source.contains("/")) {

                    errors.add(new FormatterError(
                            Severity.INFO,
                            "Consider using relative imports for project files: " + source,
                            ast.getNodeLine(importNode),
                            ast.getNodeColumn(importNode),
                            "Use relative imports (./file) for files within the same project"
                    ));
                }
            }
        }

        try {
            for (Value importNode : imports) {
                if (importNode.hasMember("specifiers") && importNode.getMember("specifiers").getArraySize() > 3) {
                    boolean hasNamespace = false;
                    String moduleName = "";

                    if (importNode.hasMember("source")) {
                        moduleName = ast.getStringProperty(importNode.getMember("source"), "value");
                    }

                    Value specifiers = importNode.getMember("specifiers");
                    for (int i = 0; i < specifiers.getArraySize(); i++) {
                        Value specifier = specifiers.getArrayElement(i);
                        if (specifier.hasMember("type") &&
                                specifier.getMember("type").asString().equals("ImportNamespaceSpecifier")) {
                            hasNamespace = true;
                            break;
                        }
                    }

                    if (!hasNamespace && specifiers.getArraySize() > 4) {
                        errors.add(new FormatterError(
                                Severity.INFO,
                                "Many named imports from " + moduleName + ". Consider using namespace import.",
                                ast.getNodeLine(importNode),
                                ast.getNodeColumn(importNode),
                                "Use namespace import (import * as name) for modules with many exports"
                        ));
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error analyzing import specifiers", e);
        }
    }

    /**
     * Check export conventions:
     * - Use named exports, not default exports
     * - Do not use .tsx or .jsx as export files
     */
    private void _checkExportConventions(JsAst ast, List<FormatterError> errors) {
        Value[] defaultExports = ast.findNodes("ExportDefaultDeclaration");

        for (Value defaultExport : defaultExports) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Default exports should be avoided",
                    ast.getNodeLine(defaultExport),
                    ast.getNodeColumn(defaultExport),
                    "Use named exports instead of default exports for better maintainability"
            ));
        }

        Value[] namedExports = ast.findNodes("ExportNamedDeclaration");
        for (Value namedExport : namedExports) {
            if (namedExport.hasMember("exportKind") &&
                    "type".equals(ast.getStringProperty(namedExport, "exportKind"))) {

                continue;
            }

            if (namedExport.hasMember("declaration") &&
                    namedExport.getMember("declaration") != null &&
                    !namedExport.getMember("declaration").isNull()) {

                Value declaration = namedExport.getMember("declaration");

                if (declaration.hasMember("type") &&
                        (declaration.getMember("type").asString().equals("TSInterfaceDeclaration") ||
                                declaration.getMember("type").asString().equals("TSTypeAliasDeclaration"))) {

                    errors.add(new FormatterError(
                            Severity.INFO,
                            "Consider using 'export type' for interface or type alias",
                            ast.getNodeLine(namedExport),
                            ast.getNodeColumn(namedExport),
                            "Use 'export type' for better type isolation in file-by-file transpilation"
                    ));
                }
            }
        }
    }

    /**
     * Check type assertion conventions:
     * - Avoid unsafe type assertions without runtime checks
     * - Use "as" syntax for assertions, not angle brackets
     * - Use unknown (not any) for intermediate type in double assertions
     */
    private void _checkTypeAssertionConventions(JsAst ast, List<FormatterError> errors) {

        Value[] tsAsExpressions = ast.findNodes("TSAsExpression");

        for (Value tsAsExpr : tsAsExpressions) {
            errors.add(new FormatterError(
                    Severity.INFO,
                    "Type assertion detected. Consider using runtime checks instead.",
                    ast.getNodeLine(tsAsExpr),
                    ast.getNodeColumn(tsAsExpr),
                    "Type assertions are unsafe and only silence the TypeScript compiler"
            ));

            if (tsAsExpr.hasMember("typeAnnotation") &&
                    tsAsExpr.getMember("typeAnnotation").hasMember("type") &&
                    tsAsExpr.getMember("typeAnnotation").getMember("type").asString().equals("TSAnyKeyword")) {

                errors.add(new FormatterError(
                        Severity.WARNING,
                        "Assertion to 'any' is unsafe and should be avoided",
                        ast.getNodeLine(tsAsExpr),
                        ast.getNodeColumn(tsAsExpr),
                        "Use explicit runtime checks or 'unknown' instead of 'any'"
                ));
            }
        }

        Value[] tsNonNullExpressions = ast.findNodes("TSNonNullExpression");

        for (Value nonNullExpr : tsNonNullExpressions) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Non-null assertion (!) is unsafe and can cause runtime errors",
                    ast.getNodeLine(nonNullExpr),
                    ast.getNodeColumn(nonNullExpr),
                    "Replace with explicit null checks (if (x) { x.foo(); })"
            ));
        }

        Value[] objectExpressions = ast.findNodes("ObjectExpression");
        for (Value objExpr : objectExpressions) {
            JsAst.AstNodeFinder finder = new JsAst.AstNodeFinder(ast);
            Value parent = finder.getParentNode(objExpr);

            if (parent != null && parent.hasMember("type") &&
                    parent.getMember("type").asString().equals("TSAsExpression")) {

                errors.add(new FormatterError(
                        Severity.INFO,
                        "Type assertion on object literal",
                        ast.getNodeLine(objExpr),
                        ast.getNodeColumn(objExpr),
                        "Use type annotations (const foo: Foo = {...}) instead of assertions (as Foo)"
                ));
            }
        }
    }

    /**
     * Check switch statement conventions:
     * - All switch statements must contain a default statement
     * - The default statement group must be last
     */
    private void _checkSwitchStatementConventions(JsAst ast, List<FormatterError> errors) {
        Value[] switchStatements = ast.findNodes("SwitchStatement");

        for (Value switchStmt : switchStatements) {
            if (!switchStmt.hasMember("cases") || switchStmt.getMember("cases").getArraySize() == 0) {
                continue;
            }

            boolean hasDefault = false;
            boolean defaultIsLast = true;
            int defaultIndex = -1;

            Value cases = switchStmt.getMember("cases");
            for (int i = 0; i < cases.getArraySize(); i++) {
                Value caseStmt = cases.getArrayElement(i);

                if (caseStmt.hasMember("test") && caseStmt.getMember("test").isNull()) {
                    hasDefault = true;
                    defaultIndex = i;

                    if (i < cases.getArraySize() - 1) {
                        defaultIsLast = false;
                    }
                }
            }

            if (!hasDefault) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "Switch statement is missing a default case",
                        ast.getNodeLine(switchStmt),
                        ast.getNodeColumn(switchStmt),
                        "Add a default case to the switch statement, even if it's empty"
                ));
            } else if (!defaultIsLast) {
                errors.add(new FormatterError(
                        Severity.INFO,
                        "Default case should be the last case in the switch statement",
                        ast.getNodeLine(cases.getArrayElement(defaultIndex)),
                        ast.getNodeColumn(cases.getArrayElement(defaultIndex)),
                        "Move the default case to be the last case in the switch statement"
                ));
            }
        }
    }
}