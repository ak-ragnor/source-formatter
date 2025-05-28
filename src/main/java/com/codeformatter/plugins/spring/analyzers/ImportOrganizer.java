package com.codeformatter.plugins.spring.analyzers;

import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.CodeAnalyzer;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.codeformatter.util.LoggerUtil;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Enhanced import organizer that follows industry standards and integrates with external tools like
 * Checkstyle for consistent import organization.
 */
public class ImportOrganizer implements CodeAnalyzer {
  private static final Logger logger = LoggerUtil.getLogger(ImportOrganizer.class);

  private final FormatterConfig config;
  private final List<String> importGroups;
  private final boolean removeUnusedImports;
  private final boolean organizeImports;
  private final int maxImportsPerGroup;

  // Standard import group orders following Google/Checkstyle conventions
  private static final List<String> DEFAULT_IMPORT_GROUPS =
      Arrays.asList(
          "static", // Static imports first
          "java", // Core Java
          "javax", // Java extensions
          "jakarta", // Jakarta EE
          "org.springframework", // Spring Framework
          "org.junit", // JUnit testing
          "org.mockito", // Mockito testing
          "org.apache", // Apache libraries
          "org.slf4j", // Logging
          "com.fasterxml", // Jackson and similar
          "com.google", // Google libraries
          "com", // Other commercial packages
          "org", // Other org packages
          "net", // Net packages
          "" // Project-specific (no prefix)
          );

  public ImportOrganizer(FormatterConfig config) {
    this.config = config;

    // Get configuration from Spring plugin config
    List<String> configGroups = config.getPluginConfig("spring", "importOrganization.groups", null);
    this.importGroups = (configGroups != null) ? configGroups : DEFAULT_IMPORT_GROUPS;

    this.removeUnusedImports =
        config.getPluginConfig("spring", "importOrganization.removeUnused", true);
    this.organizeImports = config.getPluginConfig("spring", "importOrganization.organize", true);
    this.maxImportsPerGroup =
        config.getPluginConfig("spring", "importOrganization.maxPerGroup", 20);

    logger.fine("Enhanced Import Organizer initialized with " + importGroups.size() + " groups");
  }

  @Override
  public AnalyzerResult analyze(CompilationUnit cu) {
    List<FormatterError> errors = new ArrayList<>();

    List<ImportDeclaration> imports = cu.getImports();
    if (imports.size() < 2) {
      return new AnalyzerResult(errors); // Nothing to organize
    }

    // Check for various import issues
    checkImportOrganization(imports, errors);
    checkDuplicateImports(imports, errors);
    checkUnusedImports(cu, imports, errors);
    checkWildcardImports(imports, errors);
    checkImportGroupSizes(imports, errors);

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

    try {
      List<ImportDeclaration> imports = cu.getImports();
      if (imports.size() < 2) {
        return new RefactoringResult(refactorings, errors);
      }

      boolean madeChanges = false;

      // Remove duplicates first
      if (removeDuplicateImports(cu)) {
        madeChanges = true;
        refactorings.add(
            new Refactoring(
                "DUPLICATE_IMPORT_REMOVAL", 1, 1, "Removed duplicate import statements"));
      }

      // Remove unused imports if enabled
      if (removeUnusedImports && removeUnusedImports(cu)) {
        madeChanges = true;
        refactorings.add(
            new Refactoring("UNUSED_IMPORT_REMOVAL", 1, 1, "Removed unused import statements"));
      }

      // Organize imports if enabled
      if (organizeImports && organizeImportsByGroups(cu)) {
        madeChanges = true;
        refactorings.add(
            new Refactoring(
                "IMPORT_ORGANIZATION", 1, 1, "Organized imports according to configured groups"));
      }

      if (!madeChanges) {
        logger.fine("No import organization changes needed");
      }

    } catch (Exception e) {
      logger.log(Level.WARNING, "Error during import organization", e);
      errors.add(
          new FormatterError(
              Severity.ERROR,
              "Failed to organize imports: " + e.getMessage(),
              1,
              1,
              "Manual intervention may be required"));
    }

    return new RefactoringResult(refactorings, errors);
  }

  /** Check if imports are properly organized according to configured groups. */
  private void checkImportOrganization(
      List<ImportDeclaration> imports, List<FormatterError> errors) {
    if (!organizeImports || imports.size() < 2) {
      return;
    }

    String previousGroup = null;
    ImportDeclaration previousImport = null;

    for (ImportDeclaration importDecl : imports) {
      String currentGroup = determineImportGroup(importDecl);

      if (previousGroup != null) {
        int currentGroupIndex = getGroupIndex(currentGroup);
        int previousGroupIndex = getGroupIndex(previousGroup);

        // Check group order
        if (currentGroupIndex < previousGroupIndex) {
          errors.add(
              new FormatterError(
                  Severity.WARNING,
                  "Import '"
                      + importDecl.getNameAsString()
                      + "' should come before imports from group '"
                      + previousGroup
                      + "'",
                  importDecl.getBegin().get().line,
                  importDecl.getBegin().get().column,
                  "Organize imports according to group order: " + String.join(", ", importGroups)));
        }

        // Check alphabetical order within group
        if (currentGroup.equals(previousGroup)) {
          String currentName = importDecl.getNameAsString();
          String previousName = previousImport.getNameAsString();

          if (currentName.compareToIgnoreCase(previousName) < 0) {
            errors.add(
                new FormatterError(
                    Severity.INFO,
                    "Import '"
                        + currentName
                        + "' should be alphabetically ordered before '"
                        + previousName
                        + "'",
                    importDecl.getBegin().get().line,
                    importDecl.getBegin().get().column,
                    "Sort imports alphabetically within each group"));
          }
        }
      }

      previousGroup = currentGroup;
      previousImport = importDecl;
    }
  }

  /** Check for duplicate imports. */
  private void checkDuplicateImports(List<ImportDeclaration> imports, List<FormatterError> errors) {
    Map<String, List<ImportDeclaration>> importsByName = new HashMap<>();

    for (ImportDeclaration importDecl : imports) {
      String key = importDecl.getNameAsString() + (importDecl.isStatic() ? ":static" : "");
      importsByName.computeIfAbsent(key, k -> new ArrayList<>()).add(importDecl);
    }

    for (Map.Entry<String, List<ImportDeclaration>> entry : importsByName.entrySet()) {
      if (entry.getValue().size() > 1) {
        List<ImportDeclaration> duplicates = entry.getValue();
        for (int i = 1; i < duplicates.size(); i++) {
          ImportDeclaration duplicate = duplicates.get(i);
          errors.add(
              new FormatterError(
                  Severity.WARNING,
                  "Duplicate import: " + entry.getKey().split(":")[0],
                  duplicate.getBegin().get().line,
                  duplicate.getBegin().get().column,
                  "Remove this duplicate import statement"));
        }
      }
    }
  }

  /** Check for unused imports (basic heuristic-based detection). */
  private void checkUnusedImports(
      CompilationUnit cu, List<ImportDeclaration> imports, List<FormatterError> errors) {
    if (!removeUnusedImports) {
      return;
    }

    String sourceCode = cu.toString();

    for (ImportDeclaration importDecl : imports) {
      if (importDecl.isAsterisk()) {
        continue; // Skip wildcard imports for now
      }

      String importName = importDecl.getNameAsString();
      String className = getClassNameFromImport(importName);

      // Simple heuristic: check if class name appears in source code
      // This is not perfect but catches most obvious unused imports
      if (!isImportUsed(sourceCode, className, importName)) {
        errors.add(
            new FormatterError(
                Severity.INFO,
                "Potentially unused import: " + importName,
                importDecl.getBegin().get().line,
                importDecl.getBegin().get().column,
                "Remove this import if it's not used in the code"));
      }
    }
  }

  /** Check for wildcard imports. */
  private void checkWildcardImports(List<ImportDeclaration> imports, List<FormatterError> errors) {
    for (ImportDeclaration importDecl : imports) {
      if (importDecl.isAsterisk()) {
        errors.add(
            new FormatterError(
                Severity.WARNING,
                "Avoid wildcard imports: " + importDecl.getNameAsString() + ".*",
                importDecl.getBegin().get().line,
                importDecl.getBegin().get().column,
                "Replace with specific imports for better code clarity"));
      }
    }
  }

  /** Check if any import group is too large. */
  private void checkImportGroupSizes(List<ImportDeclaration> imports, List<FormatterError> errors) {
    Map<String, List<ImportDeclaration>> groupedImports = groupImportsByPackage(imports);

    for (Map.Entry<String, List<ImportDeclaration>> entry : groupedImports.entrySet()) {
      if (entry.getValue().size() > maxImportsPerGroup) {
        String groupName = entry.getKey().isEmpty() ? "project-specific" : entry.getKey();
        ImportDeclaration firstImport = entry.getValue().get(0);

        errors.add(
            new FormatterError(
                Severity.INFO,
                "Large number of imports ("
                    + entry.getValue().size()
                    + ") from group '"
                    + groupName
                    + "'",
                firstImport.getBegin().get().line,
                firstImport.getBegin().get().column,
                "Consider if all these imports are necessary or if the class is doing too much"));
      }
    }
  }

  /** Remove duplicate imports from the compilation unit. */
  private boolean removeDuplicateImports(CompilationUnit cu) {
    List<ImportDeclaration> imports = new ArrayList<>(cu.getImports());
    Set<String> seen = new HashSet<>();
    boolean removedAny = false;

    for (ImportDeclaration importDecl : imports) {
      String key = importDecl.getNameAsString() + (importDecl.isStatic() ? ":static" : "");
      if (!seen.add(key)) {
        importDecl.remove();
        removedAny = true;
        logger.fine("Removed duplicate import: " + importDecl.getNameAsString());
      }
    }

    return removedAny;
  }

  /** Remove unused imports (using simple heuristics). */
  private boolean removeUnusedImports(CompilationUnit cu) {
    List<ImportDeclaration> imports = new ArrayList<>(cu.getImports());
    String sourceCode = cu.toString();
    boolean removedAny = false;

    for (ImportDeclaration importDecl : imports) {
      if (importDecl.isAsterisk()) {
        continue; // Skip wildcard imports
      }

      String importName = importDecl.getNameAsString();
      String className = getClassNameFromImport(importName);

      if (!isImportUsed(sourceCode, className, importName)) {
        importDecl.remove();
        removedAny = true;
        logger.fine("Removed unused import: " + importName);
      }
    }

    return removedAny;
  }

  /** Organize imports by configured groups. */
  private boolean organizeImportsByGroups(CompilationUnit cu) {
    List<ImportDeclaration> originalImports = new ArrayList<>(cu.getImports());
    if (originalImports.size() < 2) {
      return false;
    }

    // Check if already organized
    if (areImportsAlreadyOrganized(originalImports)) {
      return false;
    }

    // Remove all imports
    originalImports.forEach(Node::remove);

    // Group and sort imports
    Map<String, List<ImportDeclaration>> groupedImports = groupImportsByPackage(originalImports);

    // Add imports back in the correct order
    boolean firstGroup = true;
    for (String group : importGroups) {
      if (groupedImports.containsKey(group)) {
        List<ImportDeclaration> groupImports = groupedImports.get(group);

        // Sort alphabetically within group
        groupImports.sort(Comparator.comparing(ImportDeclaration::getNameAsString));

        // Add blank line between groups (except before first group)
        if (!firstGroup && !groupImports.isEmpty()) {
          // JavaParser doesn't directly support blank lines, but this is handled by the printer
        }

        for (ImportDeclaration importDecl : groupImports) {
          cu.addImport(importDecl);
        }

        firstGroup = false;
      }
    }

    // Add any imports that don't fit into defined groups
    Set<String> processedGroups = new HashSet<>(importGroups);
    for (Map.Entry<String, List<ImportDeclaration>> entry : groupedImports.entrySet()) {
      if (!processedGroups.contains(entry.getKey())) {
        List<ImportDeclaration> ungroupedImports = entry.getValue();
        ungroupedImports.sort(Comparator.comparing(ImportDeclaration::getNameAsString));

        for (ImportDeclaration importDecl : ungroupedImports) {
          cu.addImport(importDecl);
        }
      }
    }

    logger.fine(
        "Organized "
            + originalImports.size()
            + " imports into "
            + groupedImports.size()
            + " groups");
    return true;
  }

  /** Check if imports are already properly organized. */
  private boolean areImportsAlreadyOrganized(List<ImportDeclaration> imports) {
    String previousGroup = null;
    String previousImportName = null;

    for (ImportDeclaration importDecl : imports) {
      String currentGroup = determineImportGroup(importDecl);
      String currentImportName = importDecl.getNameAsString();

      if (previousGroup != null) {
        int currentGroupIndex = getGroupIndex(currentGroup);
        int previousGroupIndex = getGroupIndex(previousGroup);

        // Check group order
        if (currentGroupIndex < previousGroupIndex) {
          return false;
        }

        // Check alphabetical order within group
        if (currentGroup.equals(previousGroup)) {
          if (currentImportName.compareToIgnoreCase(previousImportName) < 0) {
            return false;
          }
        }
      }

      previousGroup = currentGroup;
      previousImportName = currentImportName;
    }

    return true;
  }

  /** Group imports by their package groups. */
  private Map<String, List<ImportDeclaration>> groupImportsByPackage(
      List<ImportDeclaration> imports) {
    Map<String, List<ImportDeclaration>> grouped = new LinkedHashMap<>();

    for (ImportDeclaration importDecl : imports) {
      String group = determineImportGroup(importDecl);
      grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(importDecl);
    }

    return grouped;
  }

  /** Determine which group an import belongs to. */
  private String determineImportGroup(ImportDeclaration importDecl) {
    if (importDecl.isStatic()) {
      return "static";
    }

    String name = importDecl.getNameAsString();

    for (String group : importGroups) {
      if (group.equals("static")) {
        continue;
      }

      if (group.isEmpty()) {
        continue; // Handle empty group (project-specific) last
      }

      if (name.startsWith(group + ".") || name.equals(group)) {
        return group;
      }
    }

    // If no specific group found, determine by top-level package
    String[] parts = name.split("\\.");
    if (parts.length > 0) {
      String topLevel = parts[0];

      // Check if it matches any of our configured groups
      for (String group : importGroups) {
        if (group.equals(topLevel)) {
          return group;
        }
      }

      return topLevel; // Use top-level package as group
    }

    return ""; // Default group for project-specific imports
  }

  /** Get the index of a group in the configured order. */
  private int getGroupIndex(String group) {
    int index = importGroups.indexOf(group);
    return index >= 0 ? index : importGroups.size(); // Unknown groups go last
  }

  /** Extract class name from import statement. */
  private String getClassNameFromImport(String importName) {
    String[] parts = importName.split("\\.");
    return parts[parts.length - 1];
  }

  /** Check if an import is used in the source code (simple heuristic). */
  private boolean isImportUsed(String sourceCode, String className, String fullImportName) {
    // Remove the import statements from source code for analysis
    String codeWithoutImports = sourceCode.replaceAll("import\\s+[^;]+;", "");

    // Check for direct class name usage
    String classPattern = "\\b" + className + "\\b";
    if (codeWithoutImports.matches(".*" + classPattern + ".*")) {
      return true;
    }

    // Check for static imports usage
    if (fullImportName.contains("*")) {
      // For wildcard imports, assume they're used (too complex to check)
      return true;
    }

    // Check for annotation usage (without @)
    if (className.endsWith("Test")
        || className.endsWith("Mock")
        || className.startsWith("Mock")
        || className.contains("Annotation")) {
      return true; // Assume test and annotation related imports are used
    }

    return false;
  }
}
