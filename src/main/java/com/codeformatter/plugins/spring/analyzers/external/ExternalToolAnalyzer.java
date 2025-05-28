package com.codeformatter.plugins.spring.analyzers.external;

import com.codeformatter.api.error.FormatterError;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.CodeAnalyzer;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.github.javaparser.ast.CompilationUnit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for external tool analyzers (Checkstyle, PMD, etc.). Provides common
 * functionality for integrating external Java analysis tools.
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
   * Run the external tool on the given source file.
   *
   * @param sourceFile Path to the source file
   * @param sourceCode The source code content
   * @return List of errors found by the tool
   */
  protected abstract List<FormatterError> runExternalTool(Path sourceFile, String sourceCode);

  /**
   * Get the configuration key prefix for this tool in the config file.
   *
   * @return The configuration prefix (e.g., "checkstyle", "pmd")
   */
  protected abstract String getConfigPrefix();

  /**
   * Create a temporary file for analysis if needed.
   *
   * @param sourceCode The source code to write
   * @return Path to the temporary file
   */
  protected Path createTempFile(String sourceCode) {
    try {
      Path tempFile = java.nio.file.Files.createTempFile("formatter-analysis", ".java");
      java.nio.file.Files.writeString(tempFile, sourceCode);
      tempFile.toFile().deleteOnExit();
      return tempFile;
    } catch (Exception e) {
      throw new RuntimeException("Failed to create temporary file for analysis", e);
    }
  }

  @Override
  public AnalyzerResult analyze(CompilationUnit cu) {
    List<FormatterError> errors = new ArrayList<>();

    if (!toolAvailable) {
      errors.add(
          new com.codeformatter.api.error.FormatterError(
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
          new com.codeformatter.api.error.FormatterError(
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
    return false; // Most external tools don't support auto-fixing through this interface
  }

  @Override
  public RefactoringResult applyRefactoring(CompilationUnit cu) {
    // External tools typically don't provide refactoring capabilities
    return new RefactoringResult(new ArrayList<>(), new ArrayList<>());
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
}
