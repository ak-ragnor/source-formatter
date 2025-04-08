package com.codeformatter.plugins.spring.analyzers;

import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.CodeAnalyzer;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.*;
import java.util.stream.Collectors;

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

        // Find classes in the compilation unit
        List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);

        for (ClassOrInterfaceDeclaration clazz : classes) {
            // Check for patterns
            checkSingleResponsibilityPrinciple(clazz, errors);
            checkFactoryPattern(clazz, errors);
            checkStrategyPattern(clazz, errors);
            checkBuilderPattern(clazz, errors);
            checkDecoratorPattern(clazz, errors);
        }

        return new AnalyzerResult(errors);
    }

    @Override
    public boolean canAutoFix() {
        return true;
    }

    @Override
    public RefactoringResult applyRefactoring(CompilationUnit cu) {
        if (!enforceDesignPatterns) {
            return new RefactoringResult(new ArrayList<>(), new ArrayList<>());
        }

        List<Refactoring> refactorings = new ArrayList<>();
        List<FormatterError> errors = new ArrayList<>();

        // Find classes in the compilation unit
        List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);

        for (ClassOrInterfaceDeclaration clazz : classes) {
            // Apply refactorings for pattern violations
            applyFactoryPatternRefactoring(clazz, refactorings, errors);
            applyStrategyPatternRefactoring(clazz, refactorings, errors);
            applyBuilderPatternRefactoring(clazz, refactorings, errors);
        }

        return new RefactoringResult(refactorings, errors);
    }

    /**
     * Checks if a class violates the Single Responsibility Principle
     */
    private void checkSingleResponsibilityPrinciple(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {
        // This is a simplistic check - in a real implementation, we would need more sophisticated analysis

        // Get all methods
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

        // Count the number of different "concerns" in method names
        // This is a heuristic approach - in reality, you'd need semantic analysis
        Set<String> concerns = new HashSet<>();
        for (MethodDeclaration method : methods) {
            String name = method.getNameAsString();
            if (name.startsWith("get") || name.startsWith("set") || name.startsWith("is")) {
                continue; // Skip basic accessors
            }

            // Try to extract the "concern" from the method name
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
    private void checkFactoryPattern(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {
        // Look for methods that create and return objects
        List<MethodDeclaration> factoryMethodCandidates = clazz.getMethods().stream()
                .filter(m -> m.getType().isClassOrInterfaceType())
                .filter(m -> m.getBody().isPresent())
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
                .collect(Collectors.toList());

        if (factoryMethodCandidates.size() >= 3 && !clazz.getNameAsString().endsWith("Factory")) {
            errors.add(new FormatterError(
                    Severity.INFO,
                    "Class '" + clazz.getNameAsString() + "' contains " + factoryMethodCandidates.size() +
                            " factory method candidates",
                    clazz.getBegin().get().line,
                    clazz.getBegin().get().column,
                    "Consider extracting these methods to a dedicated Factory class"
            ));
        }
    }

    /**
     * Checks for Strategy Pattern opportunities
     */
    private void checkStrategyPattern(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {
        // Look for large switch statements or if-else chains
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
                .collect(Collectors.toList());

        for (MethodDeclaration method : methodsWithSwitchOrIfChains) {
            errors.add(new FormatterError(
                    Severity.INFO,
                    "Method '" + method.getNameAsString() + "' contains complex conditional logic",
                    method.getBegin().get().line,
                    method.getBegin().get().column,
                    "Consider applying the Strategy Pattern to simplify this logic"
            ));
        }
    }

    /**
     * Checks for Builder Pattern opportunities
     */
    private void checkBuilderPattern(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {
        // Classes with many fields and constructors with many parameters are candidates for Builder pattern
        int fieldCount = clazz.getFields().size();

        // Check if any constructor has many parameters
        boolean hasComplexConstructor = clazz.getConstructors().stream()
                .anyMatch(c -> c.getParameters().size() >= 4);

        if (fieldCount >= 5 && hasComplexConstructor && !hasInnerBuilderClass(clazz)) {
            errors.add(new FormatterError(
                    Severity.INFO,
                    "Class '" + clazz.getNameAsString() + "' has " + fieldCount +
                            " fields and complex constructors",
                    clazz.getBegin().get().line,
                    clazz.getBegin().get().column,
                    "Consider implementing a Builder pattern for this class"
            ));
        }
    }

    /**
     * Checks for Decorator Pattern violations
     */
    private void checkDecoratorPattern(ClassOrInterfaceDeclaration clazz, List<FormatterError> errors) {
        // Check if class extends another class and has aggregation of same type
        if (clazz.getExtendedTypes().size() > 0) {
            String parentType = clazz.getExtendedTypes(0).getNameAsString();

            // Check if class contains a field of the parent type
            boolean hasParentTypeField = clazz.getFields().stream()
                    .anyMatch(f -> f.getVariable(0).getType().asString().equals(parentType));

            if (hasParentTypeField) {
                errors.add(new FormatterError(
                        Severity.INFO,
                        "Class '" + clazz.getNameAsString() + "' extends and contains an instance of '" +
                                parentType + "'",
                        clazz.getBegin().get().line,
                        clazz.getBegin().get().column,
                        "Consider using composition instead of inheritance (Decorator Pattern)"
                ));
            }
        }
    }

    private boolean hasInnerBuilderClass(ClassOrInterfaceDeclaration clazz) {
        return clazz.getMembers().stream()
                .filter(m -> m.isClassOrInterfaceDeclaration())
                .map(m -> (ClassOrInterfaceDeclaration) m)
                .anyMatch(c -> c.getNameAsString().equals("Builder"));
    }

    /**
     * Applies Factory Pattern refactoring
     */
    private void applyFactoryPatternRefactoring(ClassOrInterfaceDeclaration clazz,
                                                List<Refactoring> refactorings,
                                                List<FormatterError> errors) {
        // This is a complex refactoring that would require creating new classes
        // For a real implementation, we would:
        // 1. Create a new Factory class
        // 2. Move factory methods to the new class
        // 3. Update references

        // For now, we'll just return without doing anything
    }

    /**
     * Applies Strategy Pattern refactoring
     */
    private void applyStrategyPatternRefactoring(ClassOrInterfaceDeclaration clazz,
                                                 List<Refactoring> refactorings,
                                                 List<FormatterError> errors) {
        // This is a complex refactoring that would require creating new classes
        // For a real implementation, we would:
        // 1. Create an interface for the strategy
        // 2. Create concrete strategy implementations
        // 3. Replace switch/if-else logic with strategy usage

        // For now, we'll just return without doing anything
    }

    /**
     * Applies Builder Pattern refactoring
     */
    private void applyBuilderPatternRefactoring(ClassOrInterfaceDeclaration clazz,
                                                List<Refactoring> refactorings,
                                                List<FormatterError> errors) {
        // This is a complex refactoring that would require creating a builder
        // For a real implementation, we would:
        // 1. Create an inner Builder class
        // 2. Add builder methods for each field
        // 3. Add a build() method
        // 4. Possibly modify constructors

        // For now, we'll just return without doing anything
    }
}