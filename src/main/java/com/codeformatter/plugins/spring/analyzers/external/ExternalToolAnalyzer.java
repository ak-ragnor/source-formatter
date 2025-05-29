package com.codeformatter.plugins.spring.analyzers.external;

import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.CodeAnalyzer;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.github.javaparser.ast.CompilationUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for external tool analyzers (Checkstyle, PMD, etc.) with auto-fix support.
 * Provides common functionality for integrating external Java analysis tools that can both analyze
 * and automatically fix code issues.
 */
public abstract class ExternalToolAnalyzer implements CodeAnalyzer {
  protected final FormatterConfig config;
  protected final String toolName;
  protected boolean toolAvailable = false;
  protected String unavailabilityReason = null;

  protected ExternalToolAnalyzer(FormatterConfig config, String toolName) {
    this.config = config;
    this.toolName = toolName;
    this.toolAvailable = checkToolAvailability();
  }

  /**
   * Check if the external tool is available and properly configured.
   *
   * @return true if the tool is available, false otherwise
   */
  protected abstract boolean checkToolAvailability();

  /**
   * Run the external tool on the given source file for analysis only.
   *
   * @param sourceFile Path to the source file
   * @param sourceCode The source code content
   * @return List of errors found by the tool
   */
  protected abstract List<FormatterError> runExternalTool(Path sourceFile, String sourceCode);

  /**
   * Run the external tool with auto-fix capability.
   *
   * @param sourceFile Path to the source file
   * @param sourceCode The source code content
   * @return AutoFixResult containing the fixed code and applied refactorings
   */
  protected abstract AutoFixResult runExternalToolWithAutoFix(Path sourceFile, String sourceCode);

  /**
   * Get the configuration key prefix for this tool in the config file.
   *
   * @return The configuration prefix (e.g., "checkstyle", "pmd")
   */
  protected abstract String getConfigPrefix();

  /**
   * Check if this external tool supports auto-fixing.
   *
   * @return true if the tool supports auto-fix, false otherwise
   */
  protected abstract boolean supportsAutoFix();

  /**
   * Create a temporary file for analysis if needed.
   *
   * @param sourceCode The source code to write
   * @return Path to the temporary file
   */
  protected Path createTempFile(String sourceCode) {
    try {
      Path tempFile = Files.createTempFile("formatter-analysis", ".java");
      Files.writeString(tempFile, sourceCode);
      tempFile.toFile().deleteOnExit();
      return tempFile;
    } catch (Exception e) {
      throw new RuntimeException("Failed to create temporary file for analysis", e);
    }
  }

  /**
   * Read the content of a file safely.
   *
   * @param filePath Path to the file
   * @return The file content as a string
   */
  protected String readFileContent(Path filePath) {
    try {
      return Files.readString(filePath);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read file: " + filePath, e);
    }
  }

  @Override
  public AnalyzerResult analyze(CompilationUnit cu) {
    List<FormatterError> errors = new ArrayList<>();

    if (!toolAvailable) {
      errors.add(
          new FormatterError(
              com.codeformatter.api.error.Severity.WARNING,
              toolName + " is not available: " + unavailabilityReason,
              1,
              1,
              "Install " + toolName + " or disable this analyzer in configuration"));
      return new AnalyzerResult(errors);
    }

    // Check if this tool is enabled in configuration
    boolean enabled = config.getPluginConfig("spring", getConfigPrefix() + ".enabled", true);
    if (!enabled) {
      return new AnalyzerResult(errors); // Return empty results if disabled
    }

    try {
      // Convert CompilationUnit back to source code
      String sourceCode = cu.toString();

      // Create a temporary file for the external tool
      Path tempFile = createTempFile(sourceCode);

      // Run the external tool
      errors.addAll(runExternalTool(tempFile, sourceCode));

    } catch (Exception e) {
      errors.add(
          new FormatterError(
              com.codeformatter.api.error.Severity.ERROR,
              "Error running " + toolName + ": " + e.getMessage(),
              1,
              1,
              "Check " + toolName + " configuration and ensure it's properly installed"));
    }

    return new AnalyzerResult(errors);
  }

  @Override
  public boolean canAutoFix() {
    return toolAvailable && supportsAutoFix();
  }

  @Override
  public RefactoringResult applyRefactoring(CompilationUnit cu) {
    List<Refactoring> refactorings = new ArrayList<>();
    List<FormatterError> errors = new ArrayList<>();

    if (!canAutoFix()) {
      return new RefactoringResult(refactorings, errors);
    }

    // Check if auto-fix is enabled in configuration
    boolean autoFixEnabled = config.getPluginConfig("spring", getConfigPrefix() + ".autoFix", true);
    if (!autoFixEnabled) {
      return new RefactoringResult(refactorings, errors);
    }

    try {
      // Convert CompilationUnit to source code
      String originalSourceCode = cu.toString();

      // Create a temporary file for the external tool
      Path tempFile = createTempFile(originalSourceCode);

      // Run the external tool with auto-fix
      AutoFixResult result = runExternalToolWithAutoFix(tempFile, originalSourceCode);

      if (result.isSuccessful() && result.hasChanges()) {
        // Update the CompilationUnit with the fixed code
        updateCompilationUnit(cu, result.getFixedCode());

        // Add refactoring information
        refactorings.addAll(result.getRefactorings());

        // Add any remaining errors after auto-fix
        errors.addAll(result.getRemainingErrors());
      } else if (!result.isSuccessful()) {
        errors.add(
            new FormatterError(
                com.codeformatter.api.error.Severity.WARNING,
                "Auto-fix failed for " + toolName + ": " + result.getErrorMessage(),
                1,
                1,
                "Manual fixes may be required"));
      }

    } catch (Exception e) {
      errors.add(
          new FormatterError(
              com.codeformatter.api.error.Severity.ERROR,
              "Error during auto-fix with " + toolName + ": " + e.getMessage(),
              1,
              1,
              "Check " + toolName + " configuration and ensure it's properly installed"));
    }

    return new RefactoringResult(refactorings, errors);
  }

  /**
   * Update the CompilationUnit with the fixed source code.
   *
   * @param cu The compilation unit to update
   * @param fixedCode The fixed source code
   */
  private void updateCompilationUnit(CompilationUnit cu, String fixedCode) {
    try {
      // Parse the fixed code and replace the content of the original CU
      com.github.javaparser.JavaParser parser = new com.github.javaparser.JavaParser();
      com.github.javaparser.ParseResult<CompilationUnit> parseResult = parser.parse(fixedCode);

      if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
        CompilationUnit fixedCu = parseResult.getResult().get();

        // Clear the original CU and copy content from fixed CU
        cu.getPackageDeclaration().ifPresent(pkg -> cu.removePackageDeclaration());
        cu.getImports().clear();
        cu.getTypes().clear();
        cu.getComments().clear();

        // Copy package declaration
        fixedCu.getPackageDeclaration().ifPresent(pkg -> cu.setPackageDeclaration(pkg.clone()));

        // Copy imports
        fixedCu.getImports().forEach(imp -> cu.addImport(imp.clone()));

        // Copy types
        fixedCu.getTypes().forEach(type -> cu.addType(type.clone()));

        // Copy comments
        fixedCu.getComments().forEach(comment -> cu.addOrphanComment(comment.clone()));
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to update CompilationUnit with fixed code", e);
    }
  }

  /**
   * Check if the tool is available.
   *
   * @return true if available, false otherwise
   */
  public boolean isToolAvailable() {
    return toolAvailable;
  }

  /**
   * Get the reason why the tool is unavailable.
   *
   * @return The unavailability reason, or null if the tool is available
   */
  public String getUnavailabilityReason() {
    return unavailabilityReason;
  }

  /**
   * Get a configuration value specific to this tool.
   *
   * @param key The configuration key (without the tool prefix)
   * @param defaultValue The default value
   * @return The configuration value
   */
  protected <T> T getToolConfig(String key, T defaultValue) {
    return config.getPluginConfig("spring", getConfigPrefix() + "." + key, defaultValue);
  }

  /** Result of an auto-fix operation. */
  public static class AutoFixResult {
    private final boolean successful;
    private final String fixedCode;
    private final String originalCode;
    private final List<Refactoring> refactorings;
    private final List<FormatterError> remainingErrors;
    private final String errorMessage;

    public AutoFixResult(
        String originalCode,
        String fixedCode,
        List<Refactoring> refactorings,
        List<FormatterError> remainingErrors) {
      this.successful = true;
      this.originalCode = originalCode;
      this.fixedCode = fixedCode;
      this.refactorings = refactorings != null ? refactorings : new ArrayList<>();
      this.remainingErrors = remainingErrors != null ? remainingErrors : new ArrayList<>();
      this.errorMessage = null;
    }

    public AutoFixResult(String errorMessage) {
      this.successful = false;
      this.originalCode = null;
      this.fixedCode = null;
      this.refactorings = new ArrayList<>();
      this.remainingErrors = new ArrayList<>();
      this.errorMessage = errorMessage;
    }

    public boolean isSuccessful() {
      return successful;
    }

    public boolean hasChanges() {
      return successful && fixedCode != null && !fixedCode.equals(originalCode);
    }

    public String getFixedCode() {
      return fixedCode;
    }

    public String getOriginalCode() {
      return originalCode;
    }

    public List<Refactoring> getRefactorings() {
      return refactorings;
    }

    public List<FormatterError> getRemainingErrors() {
      return remainingErrors;
    }

    public String getErrorMessage() {
      return errorMessage;
    }
  }
}
