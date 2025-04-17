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
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * Analyzes Java code for adherence to coding conventions.
 */
public class JavaConventionAnalyzer implements CodeAnalyzer {
    private final FormatterConfig config;

    private static final Set<String> FUNCTIONAL_INTERFACES = Set.of(
            "Function", "Predicate", "Consumer", "Supplier", "Runnable",
            "BiFunction", "BiPredicate", "BiConsumer",
            "UnaryOperator", "BinaryOperator"
    );

    private static final Set<String> ALLOWED_CHAINING_TYPES = Set.of(
            "Stream", "Optional", "Builder", "Mockito"
    );

    private static final Pattern PLURAL_PATTERN = Pattern.compile(".*s$");

    public JavaConventionAnalyzer(FormatterConfig config) {
        this.config = config;
    }

    @Override
    public AnalyzerResult analyze(CompilationUnit cu) {
        List<FormatterError> errors = new ArrayList<>();

        _checkMethodNamingConventions(cu, errors);
        _checkVariableNamingConventions(cu, errors);
        _checkMethodChainingConventions(cu, errors);
        _checkReservedWordAlternatives(cu, errors);

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
     * Check method naming conventions:
     * - Methods returning Optional should end with "Optional"
     * - Methods returning functional interfaces should end with the interface name
     */
    private void _checkMethodNamingConventions(CompilationUnit cu, List<FormatterError> errors) {
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            String methodName = method.getNameAsString();
            Type returnType = method.getType();

            if (returnType.isClassOrInterfaceType()) {
                ClassOrInterfaceType classType = (ClassOrInterfaceType) returnType;
                String typeName = classType.getNameAsString();

                if (typeName.equals("Optional")) {
                    if (!methodName.endsWith("Optional")) {
                        errors.add(new FormatterError(
                                Severity.WARNING,
                                "Method '" + methodName + "' returns Optional but doesn't end with 'Optional'",
                                method.getBegin().get().line,
                                method.getBegin().get().column,
                                "Rename method to end with 'Optional' to follow naming convention"
                        ));
                    }
                }

                for (String functionalInterface : FUNCTIONAL_INTERFACES) {
                    if (typeName.equals(functionalInterface)) {
                        if (!methodName.endsWith(functionalInterface)) {
                            errors.add(new FormatterError(
                                    Severity.WARNING,
                                    "Method '" + methodName + "' returns " + functionalInterface +
                                            " but doesn't end with '" + functionalInterface + "'",
                                    method.getBegin().get().line,
                                    method.getBegin().get().column,
                                    "Rename method to end with '" + functionalInterface + "' to follow naming convention"
                            ));
                        }
                        break;
                    }
                }
            }
        });
    }

    /**
     * Check variable naming conventions:
     * - Collections should use pluralized type names
     * - Maps should be named as value type (plural) + Map suffix
     */
    private void _checkVariableNamingConventions(CompilationUnit cu, List<FormatterError> errors) {
        cu.findAll(VariableDeclarator.class).forEach(variable -> {
            String varName = variable.getNameAsString();
            Type varType = variable.getType();

            if (varType.isClassOrInterfaceType()) {
                ClassOrInterfaceType classType = (ClassOrInterfaceType) varType;
                String typeName = classType.getNameAsString();

                if (typeName.equals("List") || typeName.equals("Set") || typeName.equals("Collection") ||
                        typeName.equals("ArrayList") || typeName.equals("HashSet")) {

                    if (!PLURAL_PATTERN.matcher(varName).matches()) {
                        errors.add(new FormatterError(
                                Severity.INFO,
                                "Collection variable '" + varName + "' should use plural form",
                                variable.getBegin().get().line,
                                variable.getBegin().get().column,
                                "Rename variable to a plural form (e.g., 'users' instead of 'user')"
                        ));
                    }
                }

                if (typeName.equals("Map") || typeName.equals("HashMap") ||
                        typeName.equals("ConcurrentHashMap") || typeName.equals("LinkedHashMap")) {

                    if (!varName.endsWith("Map")) {
                        errors.add(new FormatterError(
                                Severity.INFO,
                                "Map variable '" + varName + "' should end with 'Map'",
                                variable.getBegin().get().line,
                                variable.getBegin().get().column,
                                "Rename variable to end with 'Map' (e.g., 'usersMap')"
                        ));
                    }
                }
            }
        });
    }

    /**
     * Check method chaining conventions:
     * - Chaining is allowed only for Stream, Optional, Builders, and Mockito
     */
    private void _checkMethodChainingConventions(CompilationUnit cu, List<FormatterError> errors) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (call.getScope().isPresent() && call.getScope().get() instanceof MethodCallExpr) {
                MethodCallExpr scope = (MethodCallExpr) call.getScope().get();

                boolean isAllowedChaining = false;

                if (scope.getScope().isPresent() && scope.getScope().get() instanceof NameExpr) {
                    NameExpr nameExpr = (NameExpr) scope.getScope().get();
                    String name = nameExpr.getNameAsString();

                    for (String allowedType : ALLOWED_CHAINING_TYPES) {
                        if (name.contains(allowedType)) {
                            isAllowedChaining = true;
                            break;
                        }
                    }
                }

                if (!isAllowedChaining) {
                    errors.add(new FormatterError(
                            Severity.INFO,
                            "Method chaining detected. Consider using intermediate variables.",
                            call.getBegin().get().line,
                            call.getBegin().get().column,
                            "Chaining is only allowed for Stream, Optional, Builders, and Mockito"
                    ));
                }
            }
        });
    }

    /**
     * Check for appropriate alternatives for reserved words
     */
    private void _checkReservedWordAlternatives(CompilationUnit cu, List<FormatterError> errors) {
        cu.findAll(VariableDeclarator.class).forEach(variable -> {
            String varName = variable.getNameAsString();

            if (varName.equals("class")) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "Variable uses reserved word 'class'",
                        variable.getBegin().get().line,
                        variable.getBegin().get().column,
                        "Use 'clazz' instead of 'class' for variables"
                ));
            } else if (varName.equals("package")) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "Variable uses reserved word 'package'",
                        variable.getBegin().get().line,
                        variable.getBegin().get().column,
                        "Use 'pkg' instead of 'package' for variables"
                ));
            }
        });
    }
}