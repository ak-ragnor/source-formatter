package com.codeformatter.plugins.spring.analyzers;

import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.CodeAnalyzer;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.codeformatter.util.LoggerUtil;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simplified analyzer that detects design pattern violations.
 * Focuses on identifying issues without complex refactoring logic.
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
                _checkComplexSwitch(clazz, errors);
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
        // Design pattern issues typically require manual fixing
        return false;
    }

    @Override
    public RefactoringResult applyRefactoring(CompilationUnit cu) {
        // Since we can't auto-fix these issues, just return an empty result
        return new RefactoringResult(new ArrayList<>(), new ArrayList<>());
    }

    /**
     * Checks if a class violates the Single Responsibility Principle
     */
    private void _checkSingleResponsibilityPrinciple(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {
        try {
            List<MethodDeclaration> methods = clazz.getMethods();

            // Too many methods can indicate violation of SRP
            if (methods.size() > 20) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "Class '" + clazz.getNameAsString() + "' has too many methods (" + methods.size() +
                                "), possibly violating the Single Responsibility Principle",
                        clazz.getBegin().get().line,
                        clazz.getBegin().get().column,
                        "Consider breaking this class into smaller, focused classes"
                ));
            }

            // Analyze method names to identify multiple concerns
            Set<String> concerns = new HashSet<>();
            for (MethodDeclaration method : methods) {
                String name = method.getNameAsString();
                if (name.startsWith("get") || name.startsWith("set") || name.startsWith("is")) {
                    continue;
                }

                // Simple heuristic: split camelCase names to extract "verb" part
                if (!name.isEmpty()) {
                    int endIndex = 1;
                    while (endIndex < name.length() &&
                            !Character.isUpperCase(name.charAt(endIndex))) {
                        endIndex++;
                    }
                    if (endIndex > 0 && endIndex < name.length()) {
                        concerns.add(name.substring(0, endIndex).toLowerCase());
                    }
                }
            }

            if (concerns.size() > 3) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "Class '" + clazz.getNameAsString() + "' appears to handle multiple concerns: " +
                                String.join(", ", concerns),
                        clazz.getBegin().get().line,
                        clazz.getBegin().get().column,
                        "Consider separating these concerns into different classes"
                ));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error checking single responsibility principle for class " +
                    clazz.getNameAsString(), e);
        }
    }

    /**
     * Checks for Factory Pattern opportunities
     */
    private void _checkFactoryPattern(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {
        try {
            // Look for factory method candidates
            List<MethodDeclaration> factoryMethodCandidates = clazz.getMethods().stream()
                    .filter(m -> m.getType().isClassOrInterfaceType())
                    .filter(m -> {
                        String returnTypeName = m.getType().asString();
                        String methodName = m.getNameAsString();
                        return (methodName.startsWith("create") || methodName.startsWith("build") ||
                                methodName.startsWith("get")) &&
                                !returnTypeName.equals("void") &&
                                !returnTypeName.equals("String") &&
                                !returnTypeName.equals("Integer") &&
                                !returnTypeName.equals("Long") &&
                                !returnTypeName.equals("Boolean");
                    })
                    .toList();

            if (factoryMethodCandidates.size() >= 3 && !clazz.getNameAsString().endsWith("Factory")) {
                int line = clazz.getBegin().map(p -> p.line).orElse(1);
                int column = clazz.getBegin().map(p -> p.column).orElse(1);

                errors.add(new FormatterError(
                        Severity.INFO,
                        "Class '" + clazz.getNameAsString() + "' contains " + factoryMethodCandidates.size() +
                                " factory method candidates",
                        line,
                        column,
                        "Consider extracting these methods to a dedicated Factory class"
                ));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error checking factory pattern for class " +
                    clazz.getNameAsString(), e);
        }
    }

    /**
     * Checks for Builder Pattern opportunities
     */
    private void _checkBuilderPattern(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {
        try {
            int fieldCount = clazz.getFields().size();

            // Classes with many fields and complex constructors are candidates for Builder pattern
            boolean hasComplexConstructor = clazz.getConstructors().stream()
                    .anyMatch(c -> c.getParameters().size() >= 4);

            if (fieldCount >= 5 && hasComplexConstructor &&
                    !clazz.getMembers().stream()
                            .filter(m -> m.isClassOrInterfaceDeclaration())
                            .anyMatch(m -> ((ClassOrInterfaceDeclaration)m).getNameAsString().equals("Builder"))) {

                int line = clazz.getBegin().map(p -> p.line).orElse(1);
                int column = clazz.getBegin().map(p -> p.column).orElse(1);

                errors.add(new FormatterError(
                        Severity.INFO,
                        "Class '" + clazz.getNameAsString() + "' has " + fieldCount +
                                " fields and complex constructors",
                        line,
                        column,
                        "Consider implementing a Builder pattern for this class"
                ));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error checking builder pattern for class " +
                    clazz.getNameAsString(), e);
        }
    }

    /**
     * Checks for complex switch statements that could benefit from Strategy pattern
     */
    private void _checkComplexSwitch(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {
        try {
            clazz.getMethods().stream()
                    .filter(m -> m.getBody().isPresent())
                    .forEach(method -> {
                        // Count switch statements
                        long switchCount = method.getBody().get()
                                .findAll(com.github.javaparser.ast.stmt.SwitchStmt.class).size();

                        // Count if-else chains
                        long ifCount = method.getBody().get()
                                .findAll(com.github.javaparser.ast.stmt.IfStmt.class).size();

                        if (switchCount > 1 || ifCount >= 3) {
                            int line = method.getBegin().map(p -> p.line).orElse(1);
                            int column = method.getBegin().map(p -> p.column).orElse(1);

                            errors.add(new FormatterError(
                                    Severity.INFO,
                                    "Method '" + method.getNameAsString() + "' contains complex conditional logic",
                                    line,
                                    column,
                                    "Consider applying the Strategy Pattern to simplify this logic"
                            ));
                        }
                    });
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error checking for complex switch statements in class " +
                    clazz.getNameAsString(), e);
        }
    }
}