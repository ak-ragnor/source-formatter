package com.codeformatter.plugins.spring.analyzers;

import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.CodeAnalyzer;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.codeformatter.util.LoggerUtil;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.SwitchStmt;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simplified analyzer that detects design pattern violations.
 * It only identifies potential issues without implementing complex refactoring logic.
 */
public class DesignPatternAnalyzer implements CodeAnalyzer {
    private static final Logger logger = LoggerUtil.getLogger(DesignPatternAnalyzer.class);

    private final FormatterConfig config;
    private final boolean enforceDesignPatterns;

    public DesignPatternAnalyzer(FormatterConfig config) {
        this.config = config;
        this.enforceDesignPatterns = config.getPluginConfig("spring", "enforceDesignPatterns", true);
    }

    @Override
    public AnalyzerResult analyze(CompilationUnit cu) {
        if (!enforceDesignPatterns) {
            return new AnalyzerResult(new ArrayList<>());
        }

        List<FormatterError> errors = new ArrayList<>();

        try {
            List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);

            for (ClassOrInterfaceDeclaration clazz : classes) {

                _checkSingleResponsibilityPrinciple(clazz, errors);
                _checkFactoryPattern(clazz, errors);
                _checkBuilderPattern(clazz, errors);
                checkStrategyPattern(clazz, errors);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error during design pattern analysis", e);
            errors.add(new FormatterError(
                    Severity.ERROR,
                    "Failed to analyze design patterns: " + e.getMessage(),
                    1, 1,
                    "This is likely a bug in the analyzer"
            ));
        }

        return new AnalyzerResult(errors);
    }

    @Override
    public boolean canAutoFix() {

        return false;
    }

    @Override
    public RefactoringResult applyRefactoring(CompilationUnit cu) {
        return new RefactoringResult(new ArrayList<>(), new ArrayList<>());
    }

    /**
     * Check if a class violates the Single Responsibility Principle
     */
    private void _checkSingleResponsibilityPrinciple(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {
        int methodCount = clazz.getMethods().size();
        if (methodCount > 15) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Class '" + clazz.getNameAsString() + "' has " + methodCount +
                            " methods, which may violate the Single Responsibility Principle",
                    clazz.getBegin().get().line,
                    clazz.getBegin().get().column,
                    "Consider breaking this class into smaller, focused classes"
            ));
        }

        int fieldCount = clazz.getFields().size();
        if (fieldCount > 10) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Class '" + clazz.getNameAsString() + "' has " + fieldCount +
                            " fields, which may violate the Single Responsibility Principle",
                    clazz.getBegin().get().line,
                    clazz.getBegin().get().column,
                    "Consider breaking this class into smaller, focused classes"
            ));
        }

        for (MethodDeclaration method : clazz.getMethods()) {
            // TODO
            // This is a simplified check - just look at method size
            if (method.getBody().isPresent()) {
                String methodBody = method.getBody().get().toString();
                int bodyLineCount = methodBody.split("\n").length;

                if (bodyLineCount > 50) {
                    errors.add(new FormatterError(
                            Severity.WARNING,
                            "Method '" + method.getNameAsString() + "' is very long (" + bodyLineCount +
                                    " lines) and may have too many responsibilities",
                            method.getBegin().get().line,
                            method.getBegin().get().column,
                            "Consider breaking this method into smaller, focused methods"
                    ));
                }
            }
        }
    }

    /**
     * Check for Factory Pattern opportunities
     */
    private void _checkFactoryPattern(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {

        List<MethodDeclaration> factoryMethodCandidates = clazz.getMethods().stream()
                .filter(m -> m.getNameAsString().startsWith("create") ||
                        m.getNameAsString().startsWith("make") ||
                        m.getNameAsString().startsWith("generate") ||
                        m.getNameAsString().startsWith("build"))
                .toList();

        if (factoryMethodCandidates.size() >= 3 && !clazz.getNameAsString().contains("Factory")) {
            errors.add(new FormatterError(
                    Severity.INFO,
                    "Class '" + clazz.getNameAsString() + "' has " + factoryMethodCandidates.size() +
                            " factory-like methods but doesn't appear to be a Factory class",
                    clazz.getBegin().get().line,
                    clazz.getBegin().get().column,
                    "Consider implementing the Factory pattern"
            ));
        }
    }

    /**
     * Check for Builder Pattern opportunities
     */
    private void _checkBuilderPattern(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {
        List<String> fieldNames = clazz.getFields().stream()
                .flatMap(f -> f.getVariables().stream()
                        .map(NodeWithSimpleName::getNameAsString))
                .toList();

        if (fieldNames.size() >= 5) {
            boolean hasComplexConstructor = clazz.getConstructors().stream()
                    .anyMatch(c -> c.getParameters().size() >= 4);

            if (hasComplexConstructor) {
                boolean hasBuilder = clazz.getMembers().stream()
                        .filter(BodyDeclaration::isClassOrInterfaceDeclaration)
                        .map(m -> (ClassOrInterfaceDeclaration)m)
                        .anyMatch(c -> c.getNameAsString().equals("Builder"));

                if (!hasBuilder) {
                    errors.add(new FormatterError(
                            Severity.INFO,
                            "Class '" + clazz.getNameAsString() + "' has " + fieldNames.size() +
                                    " fields and complex constructors - consider using Builder pattern",
                            clazz.getBegin().get().line,
                            clazz.getBegin().get().column,
                            "Implement a Builder inner class to simplify object creation"
                    ));
                }
            }
        }
    }

    /**
     * Check for Strategy Pattern opportunities via switch statements
     */
    private void checkStrategyPattern(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {
        for (MethodDeclaration method : clazz.getMethods()) {
            if (method.getBody().isPresent()) {
                List<SwitchStmt> switches = method.getBody().get().findAll(SwitchStmt.class);

                if (switches.size() > 1) {
                    errors.add(new FormatterError(
                            Severity.INFO,
                            "Method '" + method.getNameAsString() + "' has multiple switch statements",
                            method.getBegin().get().line,
                            method.getBegin().get().column,
                            "Consider using the Strategy Pattern to improve extensibility"
                    ));
                } else if (switches.size() == 1) {
                    SwitchStmt switchStmt = switches.get(0);
                    int caseCount = switchStmt.getEntries().size();

                    if (caseCount > 3) {
                        errors.add(new FormatterError(
                                Severity.INFO,
                                "Method '" + method.getNameAsString() + "' has a switch statement with " +
                                        caseCount + " cases",
                                switchStmt.getBegin().get().line,
                                switchStmt.getBegin().get().column,
                                "Consider using the Strategy Pattern to improve extensibility"
                        ));
                    }
                }

                int ifStatementCount = method.getBody().get().findAll(com.github.javaparser.ast.stmt.IfStmt.class).size();
                if (ifStatementCount >= 3) {
                    errors.add(new FormatterError(
                            Severity.INFO,
                            "Method '" + method.getNameAsString() + "' has " + ifStatementCount +
                                    " if statements which may indicate a chain of conditionals",
                            method.getBegin().get().line,
                            method.getBegin().get().column,
                            "Consider using the Strategy Pattern or Command Pattern instead of conditional logic"
                    ));
                }
            }
        }
    }
}