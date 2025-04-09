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
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes Spring components for common issues:
 * - Proper usage of Spring annotations
 * - Dependency injection issues
 * - Bean naming conventions
 * - Component organization
 */
public class SpringComponentAnalyzer implements CodeAnalyzer {
    private final FormatterConfig config;
    private final String dependencyInjectionStyle;

    private static final Set<String> COMPONENT_ANNOTATIONS = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration");

    public SpringComponentAnalyzer(FormatterConfig config) {
        this.config = config;
        this.dependencyInjectionStyle = config.getPluginConfig("spring", "enforceDependencyInjection", "constructor");
    }

    @Override
    public AnalyzerResult analyze(CompilationUnit cu) {
        List<FormatterError> errors = new ArrayList<>();

        List<ClassOrInterfaceDeclaration> springComponents = _findSpringComponents(cu);

        for (ClassOrInterfaceDeclaration component : springComponents) {
            _checkDependencyInjection(component, errors);
            _checkNamingConvention(component, errors);
            _checkAutowiring(component, errors);
            _checkQualifierUsage(component, errors);
        }

        return new AnalyzerResult(errors);
    }

    @Override
    public boolean canAutoFix() {
        return true;
    }

    @Override
    public RefactoringResult applyRefactoring(CompilationUnit cu) {
        List<Refactoring> appliedRefactorings = new ArrayList<>();
        List<FormatterError> errors = new ArrayList<>();

        List<ClassOrInterfaceDeclaration> springComponents = _findSpringComponents(cu);

        for (ClassOrInterfaceDeclaration component : springComponents) {
            if (_fixDependencyInjection(component)) {
                appliedRefactorings.add(new Refactoring(
                        "SPRING_DI_FIX",
                        component.getBegin().get().line,
                        component.getEnd().get().line,
                        "Fixed dependency injection style in " + component.getNameAsString()
                ));
            }

            if (_fixAutowiring(component)) {
                appliedRefactorings.add(new Refactoring(
                        "SPRING_AUTOWIRING_FIX",
                        component.getBegin().get().line,
                        component.getEnd().get().line,
                        "Fixed autowiring in " + component.getNameAsString()
                ));
            }

            if (_addMissingQualifiers(component)) {
                appliedRefactorings.add(new Refactoring(
                        "SPRING_QUALIFIER_FIX",
                        component.getBegin().get().line,
                        component.getEnd().get().line,
                        "Added missing @Qualifier annotations in " + component.getNameAsString()
                ));
            }
        }

        return new RefactoringResult(appliedRefactorings, errors);
    }

    private List<ClassOrInterfaceDeclaration> _findSpringComponents(CompilationUnit cu) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(this::_hasSpringComponentAnnotation)
                .collect(Collectors.toList());
    }

    private boolean _hasSpringComponentAnnotation(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotations().stream()
                .anyMatch(a -> COMPONENT_ANNOTATIONS.contains(a.getNameAsString()));
    }

    private void _checkDependencyInjection(ClassOrInterfaceDeclaration component, List<FormatterError> errors) {

        List<FieldDeclaration> autowiredFields = component.getFields().stream()
                .filter(f -> f.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Autowired") || a.getNameAsString().equals("Inject")))
                .toList();

        boolean hasAutowiredConstructor = component.getConstructors().stream()
                .anyMatch(c -> c.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Autowired") || a.getNameAsString().equals("Inject")));

        if ("constructor".equals(dependencyInjectionStyle) && !autowiredFields.isEmpty()) {
            for (FieldDeclaration field : autowiredFields) {
                errors.add(new FormatterError(
                        Severity.ERROR,
                        "Field injection detected but constructor injection is required",
                        field.getBegin().get().line,
                        field.getBegin().get().column,
                        "Convert to constructor injection"
                ));
            }
        } else if ("field".equals(dependencyInjectionStyle) && hasAutowiredConstructor) {
            errors.add(new FormatterError(
                    Severity.ERROR,
                    "Constructor injection detected but field injection is required",
                    component.getBegin().get().line,
                    component.getBegin().get().column,
                    "Convert to field injection"
            ));
        }
    }

    private void _checkNamingConvention(ClassOrInterfaceDeclaration component, List<FormatterError> errors) {
        String className = component.getNameAsString();

        if (_hasAnnotation(component, "Service") && !className.endsWith("Service") && !className.endsWith("ServiceImpl")) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Service class name should end with 'Service' or 'ServiceImpl'",
                    component.getBegin().get().line,
                    component.getBegin().get().column,
                    "Rename class to follow convention"
            ));
        }

        if (_hasAnnotation(component, "Repository") && !className.endsWith("Repository") && !className.endsWith("RepositoryImpl")) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Repository class name should end with 'Repository' or 'RepositoryImpl'",
                    component.getBegin().get().line,
                    component.getBegin().get().column,
                    "Rename class to follow convention"
            ));
        }

        if ((_hasAnnotation(component, "Controller") || _hasAnnotation(component, "RestController"))
                && !className.endsWith("Controller")) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Controller class name should end with 'Controller'",
                    component.getBegin().get().line,
                    component.getBegin().get().column,
                    "Rename class to follow convention"
            ));
        }
    }

    private boolean _hasAnnotation(ClassOrInterfaceDeclaration clazz, String annotationName) {
        return clazz.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals(annotationName));
    }

    private void _checkAutowiring(ClassOrInterfaceDeclaration component, List<FormatterError> errors) {

        component.getFields().stream()
                .filter(f -> f.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Autowired")))
                .filter(f -> !f.isPrivate())
                .forEach(f -> {
                    errors.add(new FormatterError(
                            Severity.WARNING,
                            "Autowired field should be private",
                            f.getBegin().get().line,
                            f.getBegin().get().column,
                            "Add private modifier"
                    ));
                });

        component.getFields().stream()
                .filter(f -> f.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Autowired")))
                .forEach(f -> {
                    boolean hasQualifier = f.getAnnotations().stream()
                            .anyMatch(a -> a.getNameAsString().equals("Qualifier"));

                    if (!hasQualifier && f.getVariable(0).getTypeAsString().startsWith("List<")) {
                        errors.add(new FormatterError(
                                Severity.WARNING,
                                "Collection of beans should specify @Qualifier",
                                f.getBegin().get().line,
                                f.getBegin().get().column,
                                "Add @Qualifier annotation"
                        ));
                    }
                });
    }

    private void _checkQualifierUsage(ClassOrInterfaceDeclaration component, List<FormatterError> errors) {
        // This is a simplified check - in a real implementation,
        // we would need to analyze the entire application context
        // to detect multiple beans of the same type
    }


    private boolean _fixDependencyInjection(ClassOrInterfaceDeclaration component) {
        if (!"constructor".equals(dependencyInjectionStyle)) {
            return false;
        }

        List<FieldDeclaration> autowiredFields = component.getFields().stream()
                .filter(f -> f.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Autowired")))
                .toList();

        if (autowiredFields.isEmpty()) {
            return false;
        }

        boolean changed = false;

        boolean hasConstructor = !component.getConstructors().isEmpty();

        if (!hasConstructor) {
            com.github.javaparser.ast.body.ConstructorDeclaration constructor =
                    new com.github.javaparser.ast.body.ConstructorDeclaration();
            constructor.setName(component.getNameAsString());
            constructor.setPublic(true);

            constructor.addAnnotation("Autowired");

            for (FieldDeclaration field : autowiredFields) {
                com.github.javaparser.ast.type.Type fieldType = field.getVariable(0).getType();
                String fieldName = field.getVariable(0).getNameAsString();

                com.github.javaparser.ast.body.Parameter param =
                        new com.github.javaparser.ast.body.Parameter(fieldType, fieldName);

                field.getAnnotations().stream()
                        .filter(a -> a.getNameAsString().equals("Qualifier"))
                        .findFirst()
                        .ifPresent(qualifier -> param.addAnnotation(qualifier.clone()));

                constructor.addParameter(param);
            }

            com.github.javaparser.ast.stmt.BlockStmt body = new com.github.javaparser.ast.stmt.BlockStmt();
            for (FieldDeclaration field : autowiredFields) {
                String fieldName = field.getVariable(0).getNameAsString();

                String stmt = "this." + fieldName + " = " + fieldName + ";";
                body.addStatement(stmt);

                field.getAnnotations().removeIf(a -> a.getNameAsString().equals("Autowired"));
            }

            constructor.setBody(body);

            component.addMember(constructor);
            changed = true;
        } else {
            com.github.javaparser.ast.body.ConstructorDeclaration existingConstructor =
                    component.getConstructors().get(0);

            boolean isAutowired = existingConstructor.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("Autowired"));

            if (!isAutowired) {
                existingConstructor.addAnnotation("Autowired");
                changed = true;
            }

            Set<String> paramNames = existingConstructor.getParameters().stream()
                    .map(NodeWithSimpleName::getNameAsString)
                    .collect(Collectors.toSet());

            List<FieldDeclaration> missingFields = autowiredFields.stream()
                    .filter(f -> !paramNames.contains(f.getVariable(0).getNameAsString()))
                    .toList();

            if (!missingFields.isEmpty()) {
                // This is a more complex refactoring that would modify the constructor
                // For a production implementation, you'd need to handle this case
                // by updating the constructor parameters and body
            }

            if (isAutowired || changed) {
                for (FieldDeclaration field : autowiredFields) {
                    if (paramNames.contains(field.getVariable(0).getNameAsString())) {
                        field.getAnnotations().removeIf(a -> a.getNameAsString().equals("Autowired"));
                        changed = true;
                    }
                }
            }
        }

        return changed;
    }

    private boolean _fixAutowiring(ClassOrInterfaceDeclaration component) {
        boolean changed = false;

        for (FieldDeclaration field : component.getFields()) {
            if (field.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Autowired")) && !field.isPrivate()) {
                field.setPrivate(true);
                changed = true;
            }
        }

        return changed;
    }

    private boolean _addMissingQualifiers(ClassOrInterfaceDeclaration component) {
        // This would require more complex AST manipulation and context
        return false;
    }
}