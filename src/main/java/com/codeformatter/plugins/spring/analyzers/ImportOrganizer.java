package com.codeformatter.plugins.spring.analyzers;

import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.CodeAnalyzer;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;

import java.util.*;
import java.util.stream.Collectors;

public class ImportOrganizer implements CodeAnalyzer {
    private final FormatterConfig config;
    private final List<String> importGroups;

    public ImportOrganizer(FormatterConfig config) {
        this.config = config;

        List<String> configGroups = config.getPluginConfig("spring", "importOrganization.groups", null);

        if (configGroups != null) {
            this.importGroups = configGroups;
        } else {
            this.importGroups = Arrays.asList(
                    "static",
                    "java",
                    "javax",
                    "org.springframework",
                    "com",
                    "org"
            );
        }
    }

    @Override
    public AnalyzerResult analyze(CompilationUnit cu) {
        List<FormatterError> errors = new ArrayList<>();

        List<ImportDeclaration> imports = cu.getImports();
        if (imports.size() < 2) {

            return new AnalyzerResult(errors);
        }

        if (_areImportsOrganized(imports)) {
            errors.add(new FormatterError(
                    Severity.INFO,
                    "Import statements are not organized according to convention",
                    imports.get(0).getBegin().get().line,
                    imports.get(0).getBegin().get().column,
                    "Consider organizing imports by group: " + String.join(", ", importGroups)
            ));
        }

        _checkDuplicateImports(imports, errors);

        // TODO
        // Check for unused imports (this would require more complex analysis)
        // For a simple implementation, we'll just check for static imports not being used

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

        List<ImportDeclaration> imports = cu.getImports();
        if (imports.size() < 2) {
            return new RefactoringResult(refactorings, errors);
        }

        if (_areImportsOrganized(imports)) {
            try {
                _organizeImports(cu);

                refactorings.add(new Refactoring(
                        "IMPORT_ORGANIZATION",
                        imports.get(0).getBegin().get().line,
                        imports.get(imports.size() - 1).getBegin().get().line,
                        "Organized imports according to convention"
                ));
            } catch (Exception e) {
                errors.add(new FormatterError(
                        Severity.ERROR,
                        "Failed to organize imports: " + e.getMessage(),
                        imports.get(0).getBegin().get().line,
                        imports.get(0).getBegin().get().column,
                        "Manual intervention required"
                ));
            }
        }

        Map<String, List<ImportDeclaration>> duplicates = _findDuplicateImports(imports);
        if (!duplicates.isEmpty()) {
            try {
                for (Map.Entry<String, List<ImportDeclaration>> entry : duplicates.entrySet()) {
                    List<ImportDeclaration> duplicateImports = entry.getValue();

                    for (int i = 1; i < duplicateImports.size(); i++) {
                        duplicateImports.get(i).remove();
                    }

                    refactorings.add(new Refactoring(
                            "DUPLICATE_IMPORT_REMOVAL",
                            duplicateImports.get(0).getBegin().get().line,
                            duplicateImports.get(0).getBegin().get().line,
                            "Removed duplicate imports for " + entry.getKey()
                    ));
                }
            } catch (Exception e) {
                errors.add(new FormatterError(
                        Severity.ERROR,
                        "Failed to remove duplicate imports: " + e.getMessage(),
                        1, 1,
                        "Manual intervention required"
                ));
            }
        }

        return new RefactoringResult(refactorings, errors);
    }

    private boolean _areImportsOrganized(List<ImportDeclaration> imports) {
        Map<String, List<ImportDeclaration>> groupedImports = _groupImportsByPackage(imports);

        String previousGroup = null;
        int previousLine = -1;

        for (ImportDeclaration importDecl : imports) {
            String currentGroup = _determineImportGroup(importDecl);

            if (previousGroup != null && !currentGroup.equals(previousGroup)) {
                int currentGroupIndex = importGroups.indexOf(currentGroup);
                int previousGroupIndex = importGroups.indexOf(previousGroup);

                if (currentGroupIndex < previousGroupIndex) {
                    return true;
                }
            }

            if (currentGroup.equals(previousGroup)) {
                if (importDecl.getBegin().get().line < previousLine) {
                    return true;
                }
            }

            previousGroup = currentGroup;
            previousLine = importDecl.getBegin().get().line;
        }

        return false;
    }

    private void _checkDuplicateImports(List<ImportDeclaration> imports, List<FormatterError> errors) {
        Map<String, List<ImportDeclaration>> duplicates = _findDuplicateImports(imports);

        for (Map.Entry<String, List<ImportDeclaration>> entry : duplicates.entrySet()) {
            List<ImportDeclaration> duplicateImports = entry.getValue();
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Duplicate import: " + entry.getKey() + " appears " + duplicateImports.size() + " times",
                    duplicateImports.get(0).getBegin().get().line,
                    duplicateImports.get(0).getBegin().get().column,
                    "Consider removing duplicate imports"
            ));
        }
    }

    private Map<String, List<ImportDeclaration>> _findDuplicateImports(List<ImportDeclaration> imports) {
        Map<String, List<ImportDeclaration>> importsByName = new HashMap<>();

        for (ImportDeclaration importDecl : imports) {
            String name = importDecl.getNameAsString();
            importsByName.computeIfAbsent(name, k -> new ArrayList<>()).add(importDecl);
        }

        return importsByName.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void _organizeImports(CompilationUnit cu) {
        List<ImportDeclaration> imports = new ArrayList<>(cu.getImports());

        imports.forEach(Node::remove);

        Map<String, List<ImportDeclaration>> groupedImports = _groupImportsByPackage(imports);

        for (String group : importGroups) {
            if (groupedImports.containsKey(group)) {
                List<ImportDeclaration> groupImports = groupedImports.get(group);

                groupImports.sort(Comparator.comparing(ImportDeclaration::getNameAsString));

                for (ImportDeclaration importDecl : groupImports) {
                    cu.addImport(importDecl);
                }
            }
        }

        Set<String> knownGroups = new HashSet<>(importGroups);
        for (Map.Entry<String, List<ImportDeclaration>> entry : groupedImports.entrySet()) {
            if (!knownGroups.contains(entry.getKey())) {
                for (ImportDeclaration importDecl : entry.getValue()) {
                    cu.addImport(importDecl);
                }
            }
        }
    }

    private Map<String, List<ImportDeclaration>> _groupImportsByPackage(List<ImportDeclaration> imports) {
        Map<String, List<ImportDeclaration>> groupedImports = new HashMap<>();

        for (ImportDeclaration importDecl : imports) {
            String group = _determineImportGroup(importDecl);
            groupedImports.computeIfAbsent(group, k -> new ArrayList<>()).add(importDecl);
        }

        return groupedImports;
    }

    private String _determineImportGroup(ImportDeclaration importDecl) {
        if (importDecl.isStatic()) {
            return "static";
        }

        String name = importDecl.getNameAsString();

        for (String group : importGroups) {
            if (group.equals("static")) {
                continue;
            }

            if (name.startsWith(group + ".") || name.equals(group)) {
                return group;
            }
        }

        String[] parts = name.split("\\.");
        if (parts.length > 0) {
            return parts[0];
        }

        return "other";
    }
}