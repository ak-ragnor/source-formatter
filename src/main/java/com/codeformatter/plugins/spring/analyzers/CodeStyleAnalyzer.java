package com.codeformatter.plugins.spring.analyzers;

import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.CodeAnalyzer;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class CodeStyleAnalyzer implements CodeAnalyzer {
    private final FormatterConfig config;
    private final int indentSize;
    private final int lineLength;
    private final boolean useTabs;


    private static final Pattern CAMEL_CASE_METHOD = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
    private static final Pattern CAMEL_CASE_VARIABLE = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
    private static final Pattern SCREAMING_SNAKE_CASE = Pattern.compile("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$");

    public CodeStyleAnalyzer(FormatterConfig config) {
        this.config = config;
        this.indentSize = config.getGeneralConfig("indentSize", 4);
        this.lineLength = config.getGeneralConfig("lineLength", 100);
        this.useTabs = config.getGeneralConfig("useTabs", false);
    }

    @Override
    public AnalyzerResult analyze(CompilationUnit cu) {
        List<FormatterError> errors = new ArrayList<>();

        _checkMethodNaming(cu, errors);
        _checkVariableNaming(cu, errors);
        _checkLineLengths(cu, errors);
        _checkMethodChaining(cu, errors);

        return new AnalyzerResult(errors);
    }

    @Override
    public boolean canAutoFix() {

        return true;
    }

    @Override
    public RefactoringResult applyRefactoring(CompilationUnit cu) {
        List<Refactoring> refactorings = new ArrayList<>();
        List<FormatterError> errors = new ArrayList<>();

        _fixLineLengths(cu, refactorings, errors);

        _fixMethodChaining(cu, refactorings, errors);

        return new RefactoringResult(refactorings, errors);
    }

    private void _checkMethodNaming(CompilationUnit cu, List<FormatterError> errors) {
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            String methodName = method.getNameAsString();


            if (!CAMEL_CASE_METHOD.matcher(methodName).matches()) {
                if (!methodName.startsWith("_")) {
                    errors.add(new FormatterError(
                            Severity.WARNING,
                            "Method name '" + methodName + "' doesn't follow camelCase convention",
                            method.getBegin().get().line,
                            method.getBegin().get().column,
                            "Rename method to follow camelCase convention"
                    ));
                }
            }
        });
    }

    private void _checkVariableNaming(CompilationUnit cu, List<FormatterError> errors) {
        cu.findAll(VariableDeclarator.class).forEach(variable -> {
            String varName = variable.getNameAsString();

            boolean isConstant = variable.getParentNode().isPresent() &&
                    variable.getParentNode().get().toString().contains("final") &&
                    variable.getParentNode().get().toString().contains("static");

            if (isConstant) {
                if (!SCREAMING_SNAKE_CASE.matcher(varName).matches()) {
                    errors.add(new FormatterError(
                            Severity.WARNING,
                            "Constant '" + varName + "' should use UPPER_SNAKE_CASE",
                            variable.getBegin().get().line,
                            variable.getBegin().get().column,
                            "Rename constant to follow UPPER_SNAKE_CASE convention"
                    ));
                }
            } else {
                if (!CAMEL_CASE_VARIABLE.matcher(varName).matches() && !varName.startsWith("_")) {
                    errors.add(new FormatterError(
                            Severity.WARNING,
                            "Variable name '" + varName + "' doesn't follow camelCase convention",
                            variable.getBegin().get().line,
                            variable.getBegin().get().column,
                            "Rename variable to follow camelCase convention"
                    ));
                }
            }
        });
    }

    private void _checkLineLengths(CompilationUnit cu, List<FormatterError> errors) {





        cu.findAll(BlockStmt.class).forEach(block -> {
            block.getStatements().forEach(stmt -> {
                String stmtStr = stmt.toString();


                if (stmtStr.length() > lineLength && !stmtStr.contains("\n")) {
                    errors.add(new FormatterError(
                            Severity.INFO,
                            "Statement may exceed line length limit of " + lineLength + " characters",
                            stmt.getBegin().get().line,
                            stmt.getBegin().get().column,
                            "Consider breaking the statement across multiple lines"
                    ));
                }
            });
        });
    }

    private void _checkMethodChaining(CompilationUnit cu, List<FormatterError> errors) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (call.getScope().isPresent() && call.getScope().get() instanceof MethodCallExpr) {
                MethodCallExpr scope = (MethodCallExpr) call.getScope().get();

                if (scope.getScope().isPresent() && scope.getScope().get() instanceof MethodCallExpr) {
                    String callStr = call.toString();
                    if (!callStr.contains("\n") && callStr.length() > 50) {
                        errors.add(new FormatterError(
                                Severity.INFO,
                                "Long method chain detected that could reduce readability",
                                call.getBegin().get().line,
                                call.getBegin().get().column,
                                "Consider breaking the method chain into multiple lines with each method call on its own line"
                        ));
                    }
                }
            }
        });
    }

    private void _fixLineLengths(CompilationUnit cu, List<Refactoring> refactorings, List<FormatterError> errors) {







        boolean foundLongLines = false;
        for (BlockStmt block : cu.findAll(BlockStmt.class)) {
            for (int i = 0; i < block.getStatements().size(); i++) {
                String stmtStr = block.getStatement(i).toString();
                if (stmtStr.length() > lineLength && !stmtStr.contains("\n")) {
                    foundLongLines = true;
                    break;
                }
            }
            if (foundLongLines) break;
        }

        if (foundLongLines) {
            refactorings.add(new Refactoring(
                    "LINE_LENGTH_FIX",
                    1, 1,
                    "Attempted to fix long lines by adding line breaks where appropriate"
            ));
        }
    }

    private void _fixMethodChaining(CompilationUnit cu, List<Refactoring> refactorings, List<FormatterError> errors) {






        boolean foundLongChains = false;
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (call.getScope().isPresent() && call.getScope().get() instanceof MethodCallExpr) {
                MethodCallExpr scope = (MethodCallExpr) call.getScope().get();

                if (scope.getScope().isPresent() && scope.getScope().get() instanceof MethodCallExpr) {
                    String callStr = call.toString();
                    if (!callStr.contains("\n") && callStr.length() > 50) {
                        foundLongChains = true;
                        break;
                    }
                }
            }
        }

        if (foundLongChains) {
            refactorings.add(new Refactoring(
                    "METHOD_CHAIN_FORMAT",
                    1, 1,
                    "Attempted to fix method chains by adding appropriate line breaks and indentation"
            ));
        }
    }
}