package com.codeformatter.plugins.spring.analyzers;

import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.CodeAnalyzer;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.List;

public class MethodSizeAnalyzer implements CodeAnalyzer {
    private final int maxMethodLines;
    private final int maxMethodComplexity;

    public MethodSizeAnalyzer(FormatterConfig config) {
        this.maxMethodLines = config.getPluginConfig("spring", "maxMethodLines", 50);
        this.maxMethodComplexity = config.getPluginConfig("spring", "maxMethodComplexity", 15);
    }

    @Override
    public AnalyzerResult analyze(CompilationUnit cu) {
        List<FormatterError> errors = new ArrayList<>();

        cu.findAll(MethodDeclaration.class).forEach(method -> {
            if (method.getBody().isPresent()) {

                BlockStmt body = method.getBody().get();
                int lineCount = _countMethodLines(body);

                if (lineCount > maxMethodLines) {
                    int line = method.getBegin().map(p -> p.line).orElse(1);
                    int column = method.getBegin().map(p -> p.column).orElse(1);

                    errors.add(new FormatterError(
                            Severity.ERROR,
                            "Method '" + method.getNameAsString() + "' is too long (" + lineCount +
                                    " lines, max allowed is " + maxMethodLines + ")",
                            line,
                            column,
                            "Consider breaking this method into smaller helper methods"
                    ));
                }

                int complexity = _calculateComplexity(body);
                if (complexity > maxMethodComplexity) {
                    int line = method.getBegin().map(p -> p.line).orElse(1);
                    int column = method.getBegin().map(p -> p.column).orElse(1);

                    errors.add(new FormatterError(
                            Severity.ERROR,
                            "Method '" + method.getNameAsString() + "' is too complex (complexity " +
                                    complexity + ", max allowed is " + maxMethodComplexity + ")",
                            line,
                            column,
                            "Consider extracting complex logic into separate methods"
                    ));
                }
            }
        });

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

        cu.findAll(MethodDeclaration.class).forEach(method -> {
            if (method.getBody().isPresent()) {
                BlockStmt body = method.getBody().get();

                int lineCount = _countMethodLines(body);
                if (lineCount > maxMethodLines) {
                    try {
                        boolean refactored = _refactorLargeMethod(method);

                        if (refactored) {
                            refactorings.add(new Refactoring(
                                    "METHOD_EXTRACTION",
                                    method.getBegin().get().line,
                                    method.getEnd().get().line,
                                    "Broke up large method '" + method.getNameAsString() + "' into smaller methods"
                            ));
                        } else {
                            errors.add(new FormatterError(
                                    Severity.WARNING,
                                    "Could not automatically refactor large method '" + method.getNameAsString() + "'",
                                    method.getBegin().get().line,
                                    method.getBegin().get().column,
                                    "Manual intervention required to break up this method"
                            ));
                        }
                    } catch (Exception e) {
                        errors.add(new FormatterError(
                                Severity.ERROR,
                                "Error during method refactoring: " + e.getMessage(),
                                method.getBegin().get().line,
                                method.getBegin().get().column
                        ));
                    }
                }
            }
        });

        return new RefactoringResult(refactorings, errors);
    }

    private int _countMethodLines(BlockStmt body) {
        if (body.getEnd().isPresent() && body.getBegin().isPresent()) {
            return body.getEnd().get().line - body.getBegin().get().line + 1;
        }
        return 0;
    }

    private int _calculateComplexity(BlockStmt body) {
        int complexity = 1;

        complexity += body.findAll(com.github.javaparser.ast.stmt.IfStmt.class).size();
        complexity += body.findAll(com.github.javaparser.ast.stmt.ForStmt.class).size();
        complexity += body.findAll(com.github.javaparser.ast.stmt.ForEachStmt.class).size();
        complexity += body.findAll(com.github.javaparser.ast.stmt.WhileStmt.class).size();
        complexity += body.findAll(com.github.javaparser.ast.stmt.DoStmt.class).size();
        complexity += body.findAll(com.github.javaparser.ast.stmt.SwitchEntry.class).size();

        return complexity;
    }

    private boolean _refactorLargeMethod(MethodDeclaration method) {

        if (!method.getBody().isPresent()) {
            return false;
        }

        BlockStmt body = method.getBody().get();
        List<Statement> statements = body.getStatements();

        if (statements.size() < 10) {
            return false;
        }

        Node parent = method.getParentNode().orElse(null);
        if (!(parent instanceof ClassOrInterfaceDeclaration)) {
            return false;
        }

        ClassOrInterfaceDeclaration classDecl = (ClassOrInterfaceDeclaration) parent;

        int splitPoint = statements.size() / 2;

        MethodDeclaration helperMethod = new MethodDeclaration();
        helperMethod.setName(method.getNameAsString() + "Helper");
        helperMethod.setType(method.getType());
        helperMethod.setModifiers(NodeList.nodeList(Modifier.privateModifier()));

        for (Parameter param : method.getParameters()) {
            helperMethod.addParameter(param.clone());
        }


        BlockStmt helperBody = new BlockStmt();
        for (int i = splitPoint; i < statements.size(); i++) {
            helperBody.addStatement(statements.get(i).clone());
        }
        helperMethod.setBody(helperBody);


        classDecl.addMember(helperMethod);


        BlockStmt newBody = new BlockStmt();
        for (int i = 0; i < splitPoint; i++) {
            newBody.addStatement(statements.get(i).clone());
        }

        StringBuilder callStmt = new StringBuilder(helperMethod.getNameAsString() + "(");
        for (int i = 0; i < method.getParameters().size(); i++) {
            if (i > 0) callStmt.append(", ");
            callStmt.append(method.getParameter(i).getNameAsString());
        }
        callStmt.append(");");
        newBody.addStatement(callStmt.toString());

        method.setBody(newBody);

        return true;
    }
}
