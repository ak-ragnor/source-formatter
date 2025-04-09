package com.codeformatter.plugins.spring.analyzers;

import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.CodeAnalyzer;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.*;

/**
 * Analyzer that detects design pattern violations and opportunities to apply design patterns.
 */
public class DesignPatternAnalyzer implements CodeAnalyzer {
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

        List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);

        for (ClassOrInterfaceDeclaration clazz : classes) {
            _checkSingleResponsibilityPrinciple(clazz, errors);
            _checkFactoryPattern(clazz, errors);
            _checkStrategyPattern(clazz, errors);
            _checkBuilderPattern(clazz, errors);
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
     * Checks if a class violates the Single Responsibility Principle
     */
    private void _checkSingleResponsibilityPrinciple(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {

        List<MethodDeclaration> methods = clazz.getMethods();

        if (methods.size() > 20) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Class '" + clazz.getNameAsString() + "' has too many methods (" + methods.size() +
                            "), violating the Single Responsibility Principle",
                    clazz.getBegin().get().line,
                    clazz.getBegin().get().column,
                    "Consider breaking this class into smaller, focused classes"
            ));
        }

        Set<String> concerns = new HashSet<>();
        for (MethodDeclaration method : methods) {
            String name = method.getNameAsString();
            if (name.startsWith("get") || name.startsWith("set") || name.startsWith("is")) {
                continue;
            }

            String[] words = name.split("(?=[A-Z])");
            if (words.length > 0) {
                concerns.add(words[0].toLowerCase());
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
    }

    /**
     * Checks for Factory Pattern opportunities
     */
    private void _checkFactoryPattern(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {

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
    }

    /**
     * Checks for Strategy Pattern opportunities
     */
    private void _checkStrategyPattern(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {

        List<MethodDeclaration> methodsWithSwitchOrIfChains = clazz.getMethods().stream()
                .filter(m -> m.getBody().isPresent())
                .filter(m -> {
                    // Check for switch statements
                    long switchCount = m.getBody().get().findAll(com.github.javaparser.ast.stmt.SwitchStmt.class).size();
                    if (switchCount > 0) {
                        return true;
                    }

                    // Check for long if-else chains
                    long ifCount = m.getBody().get().findAll(com.github.javaparser.ast.stmt.IfStmt.class).size();
                    return ifCount >= 3;
                })
                .toList();

        for (MethodDeclaration method : methodsWithSwitchOrIfChains) {
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
    }

    /**
     * Checks for Builder Pattern opportunities
     */
    private void _checkBuilderPattern(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {
        int fieldCount = clazz.getFields().size();

        boolean hasComplexConstructor = clazz.getConstructors().stream()
                .anyMatch(c -> c.getParameters().size() >= 4);

        if (fieldCount >= 5 && hasComplexConstructor && !_hasInnerBuilderClass(clazz)) {
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
    }

    private boolean _hasInnerBuilderClass(ClassOrInterfaceDeclaration clazz) {
        return clazz.getMembers().stream()
                .filter(BodyDeclaration::isClassOrInterfaceDeclaration)
                .map(m -> (ClassOrInterfaceDeclaration) m)
                .anyMatch(c -> c.getNameAsString().equals("Builder"));
    }

}