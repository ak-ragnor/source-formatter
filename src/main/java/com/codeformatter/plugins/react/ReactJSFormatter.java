package com.codeformatter.plugins.react;

import com.codeformatter.api.FormatterPlugin;
import com.codeformatter.api.FormatterResult;
import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.util.LoggerUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * React JS formatter plugin using Node.js tools for formatting and analysis. This implementation
 * uses NodeJsServer to communicate with a Node.js process for JavaScript/React code processing.
 *
 * <p>This version includes improved error handling and fallback options when Node.js is
 * unavailable.
 */
public class ReactJSFormatter implements FormatterPlugin, AutoCloseable {
  private static final Logger logger = LoggerUtil.getLogger(ReactJSFormatter.class);

  private FormatterConfig config;
  private NodeJsServer server;
  private final Map<String, FormatterResult> resultCache = new ConcurrentHashMap<>();
  private boolean nodeJsAvailable = false;
  private boolean disabled = false;
  private String disabledReason = null;

  @Override
  public void initialize(FormatterConfig config) {
    this.config = config;
    // Use singleton instance to avoid creating multiple Node.js servers
    this.server = NodeJsServer.getInstance();

    // Check if Node.js is available first, and disable if not
    nodeJsAvailable = server.isNodeJsAvailable();
    if (!nodeJsAvailable) {
      disabledReason = "Node.js not available: " + server.getLastError();
      disabled = true;
      logger.warning("ReactJSFormatter disabled: " + disabledReason);

      // Avoid trying to configure if Node.js is unavailable
      return;
    }

    // Try to start the server and configure it
    try {
      boolean started = server.startServer();
      if (!started) {
        disabledReason = "Node.js server failed to start: " + server.getLastError();
        disabled = true;
        logger.warning("ReactJSFormatter disabled: " + disabledReason);
        return;
      }

      Map<String, Object> formatterOptions = createFormatterOptions(config);
      boolean configured = server.configure(formatterOptions);

      if (!configured) {
        logger.warning("Failed to configure Node.js server, will use default settings");
      } else {
        logger.info("ReactJSFormatter initialized with Node.js server");
      }
    } catch (Exception e) {
      disabledReason = "Error initializing: " + e.getMessage();
      disabled = true;
      logger.log(Level.WARNING, "ReactJSFormatter disabled: " + disabledReason, e);
    }
  }

  /** Create formatter options map from FormatterConfig. */
  private Map<String, Object> createFormatterOptions(FormatterConfig config) {
    Map<String, Object> options = new HashMap<>();

    options.put("printWidth", config.getGeneralConfig("lineLength", 100));
    options.put("tabWidth", config.getGeneralConfig("indentSize", 2));
    options.put("useTabs", config.getGeneralConfig("useTabs", false));

    options.put("jsxBracketSameLine", false);
    options.put("singleQuote", true);

    Map<String, Object> reactConfig = new HashMap<>();
    if (config.getPluginConfigsMap().containsKey("react")) {
      reactConfig.putAll(config.getPluginConfigsMap().get("react"));
    }

    options.put("maxComponentLines", reactConfig.getOrDefault("maxComponentLines", 150));
    options.put(
        "enforceHookDependencies", reactConfig.getOrDefault("enforceHookDependencies", true));
    options.put("extractComponents", reactConfig.getOrDefault("extractComponents", true));
    options.put("jsxLineBreakRule", reactConfig.getOrDefault("jsxLineBreakRule", "multiline"));

    return options;
  }

  @Override
  public FormatterResult format(Path filePath, String sourceCode) {
    if (sourceCode == null || sourceCode.trim().isEmpty()) {
      return FormatterResult.builder().successful(true).formattedCode(sourceCode).build();
    }

    // If formatter is disabled, return a special result explaining why
    if (disabled) {
      FormatterError error =
          new FormatterError(
              Severity.WARNING,
              "ReactJS formatter is disabled: " + disabledReason,
              1,
              1,
              "Install Node.js and required npm packages (prettier, eslint, eslint-plugin-react, eslint-plugin-react-hooks)");

      return FormatterResult.builder()
          .successful(false)
          .formattedCode(sourceCode) // Return original code unformatted
          .addError(error)
          .build();
    }

    String contentHash = _calculateHash(sourceCode);

    if (resultCache.containsKey(contentHash)) {
      logger.fine("Cache hit for " + filePath);
      return resultCache.get(contentHash);
    }

    List<FormatterError> errors = new ArrayList<>();
    List<Refactoring> refactorings = new ArrayList<>();
    String formattedCode = sourceCode;
    boolean lintingSucceeded = false;

    try {
      boolean isReact = isReactFile(filePath, sourceCode);
      logger.fine("Processing " + filePath + " (React: " + isReact + ")");

      // Format the code using Node.js server
      formattedCode = server.formatCode(sourceCode, isReact);

      // Analyze the code using ESLint - separate process from formatting
      try {
        List<NodeJsServer.LintIssue> lintIssues = server.analyzeCode(sourceCode, isReact);

        // Create a proper ReactAnalyzerResult
        ReactAnalyzerResult analyzerResult =
            new ReactAnalyzerResult(_convertLintIssuesToErrors(lintIssues));

        // Add analyzer errors to our error list
        errors.addAll(analyzerResult.getErrors());
        lintingSucceeded = true;

        // For React files, analyze for hook dependencies
        if (isReact && config.getPluginConfig("react", "enforceHookDependencies", true)) {
          // Check for potential hook issues and add refactorings
          ReactRefactoringResult refactoringResult =
              analyzeHookDependencies(sourceCode, formattedCode);
          errors.addAll(refactoringResult.getErrors());
          refactorings.addAll(refactoringResult.getAppliedRefactorings());
        }

      } catch (IOException e) {
        logger.log(Level.WARNING, "Linting failed but formatting succeeded: " + e.getMessage(), e);

        // Use ERROR severity to ensure it shows in output
        errors.add(
            new FormatterError(
                Severity.ERROR, // Changed from WARNING to ERROR to make sure it shows up
                "ESLint analysis failed: " + e.getMessage(),
                1,
                1,
                "Install missing ESLint plugins or check server configuration"));
      }

      // If formatting changed the code, add a refactoring
      if (!formattedCode.equals(sourceCode)) {
        refactorings.add(
            new Refactoring(
                "FORMATTING",
                1,
                countLines(sourceCode),
                "Applied " + (isReact ? "React" : "JavaScript") + " formatting"));
      }

    } catch (IOException e) {
      logger.log(Level.WARNING, "Error processing " + filePath + ": " + e.getMessage(), e);

      errors.add(
          new FormatterError(
              Severity.WARNING,
              "Error processing with Node.js: " + e.getMessage(),
              1,
              1,
              "Make sure Node.js is installed and required npm packages are available: prettier, eslint, eslint-plugin-react"));

      // Use original code if formatting failed
      formattedCode = sourceCode;
    }

    // If linting didn't succeed but formatting did, we should let the user know
    if (!lintingSucceeded && !formattedCode.equals(sourceCode)) {
      errors.add(
          new FormatterError(
              Severity.WARNING,
              "Code was formatted successfully but ESLint analysis was skipped",
              1,
              1,
              "Install missing ESLint plugins to enable full code analysis"));
    }

    // Determine if formatting was successful
    // Format is successful if there are no FATAL errors, even if there are regular errors or
    // warnings
    boolean successful = errors.stream().noneMatch(e -> e.getSeverity() == Severity.FATAL);

    FormatterResult result =
        FormatterResult.builder()
            .successful(successful)
            .formattedCode(formattedCode)
            .errors(errors)
            .appliedRefactorings(refactorings)
            .build();

    // Cache the result
    resultCache.put(contentHash, result);

    return result;
  }

  /** Analyze React code for hook dependencies issues and create refactoring suggestions */
  private ReactRefactoringResult analyzeHookDependencies(
      String originalCode, String formattedCode) {
    List<FormatterError> errors = new ArrayList<>();
    List<Refactoring> refactorings = new ArrayList<>();

    // Simple heuristic: check if empty dependency arrays were modified
    if (originalCode.contains("useEffect(() => {") && originalCode.contains("}, [])")) {
      if (formattedCode.contains("}, [") && !formattedCode.contains("}, []")) {
        refactorings.add(
            new Refactoring(
                "HOOK_DEPENDENCIES_FIX",
                1,
                countLines(originalCode),
                "Fixed React hook dependencies"));

        // Add a warning about the hook dependency issue
        errors.add(
            new FormatterError(
                Severity.WARNING,
                "React hook is missing dependencies in dependency array",
                1,
                1,
                "Add all dependencies used in the effect to its dependency array"));
      }
    }

    // Check for useCallback without dependencies
    if (originalCode.contains("useCallback(") && originalCode.contains("}, [])")) {
      errors.add(
          new FormatterError(
              Severity.WARNING,
              "useCallback hook appears to be missing dependencies",
              1,
              1,
              "Add all dependencies used in the callback to its dependency array"));
    }

    return new ReactRefactoringResult(refactorings, errors);
  }

  /** Check if hook dependency fixing was applied and add a refactoring if so. */
  private void addHookDependencyRefactoring(
      String originalCode, String formattedCode, List<Refactoring> refactorings) {
    // Simple heuristic: check if empty dependency arrays were modified
    if (originalCode.contains("useEffect(() => {") && originalCode.contains("}, [])")) {
      if (formattedCode.contains("}, [") && !formattedCode.contains("}, []")) {
        refactorings.add(
            new Refactoring(
                "HOOK_DEPENDENCIES_FIX",
                1,
                countLines(originalCode),
                "Fixed React hook dependencies"));
      }
    }
  }

  /** Count the number of lines in a string. */
  private int countLines(String str) {
    if (str == null || str.isEmpty()) {
      return 0;
    }
    return str.split("\n").length;
  }

  /** Calculate a hash of the source code for caching. */
  private String _calculateHash(String sourceCode) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(sourceCode.getBytes());

      // Convert to hex string
      StringBuilder hexString = new StringBuilder();
      for (byte b : hash) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) hexString.append('0');
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (NoSuchAlgorithmException e) {
      logger.warning("Failed to calculate hash, defaulting to code length: " + e.getMessage());
      return "length-" + sourceCode.length();
    }
  }

  /** Convert ESLint issues to formatter errors. */
  private List<FormatterError> _convertLintIssuesToErrors(List<NodeJsServer.LintIssue> lintIssues) {
    return lintIssues.stream()
        .map(
            issue -> {
              Severity severity =
                  switch (issue.severity()) {
                    case "error" -> Severity.ERROR;
                    case "warning" -> Severity.WARNING;
                    default -> Severity.INFO;
                  };

              String suggestion = null;
              if (issue.ruleId() != null && !issue.ruleId().isEmpty()) {
                if (issue.ruleId().equals("react-hooks/exhaustive-deps")) {
                  suggestion = "Add all dependencies used in the effect to its dependency array";
                } else if (issue.ruleId().equals("react-hooks/rules-of-hooks")) {
                  suggestion = "Ensure hooks are only called at the top level of your component";
                } else {
                  suggestion = "See ESLint rule: " + issue.ruleId();
                }
              }

              return new FormatterError(
                  severity, issue.message(), issue.line(), issue.column(), suggestion);
            })
        .collect(Collectors.toList());
  }

  /** Determine if a file is a React/JSX file based on extension and content. */
  private boolean isReactFile(Path filePath, String sourceCode) {
    String fileName = filePath.toString().toLowerCase();

    // Check by extension first
    if (fileName.endsWith(".jsx") || fileName.endsWith(".tsx")) {
      return true;
    }

    // For .js files, check content for React patterns
    if (fileName.endsWith(".js") || fileName.endsWith(".ts")) {
      return containsReactCode(sourceCode);
    }

    return false;
  }

  /** Check if code contains React patterns. */
  private boolean containsReactCode(String sourceCode) {
    return sourceCode.contains("import React")
        || sourceCode.contains("from 'react'")
        || sourceCode.contains("from \"react\"")
        || sourceCode.contains("React.")
        || (sourceCode.contains("<") && sourceCode.contains("/>"))
        || sourceCode.contains("useState(")
        || sourceCode.contains("useEffect(")
        || sourceCode.contains("useRef(")
        || sourceCode.contains("useCallback(")
        || sourceCode.contains("extends Component");
  }

  /** Clear the formatter's cache. */
  public void clearCache() {
    resultCache.clear();
    logger.info("ReactJSFormatter cache cleared");
  }

  /** Get the cache size. */
  public int getCacheSize() {
    return resultCache.size();
  }

  /** Check if the formatter is disabled. */
  public boolean isDisabled() {
    return disabled;
  }

  /** Get the reason why the formatter is disabled, if it is. */
  public String getDisabledReason() {
    return disabledReason;
  }

  /** Check if Node.js is available. */
  public boolean isNodeJsAvailable() {
    return nodeJsAvailable;
  }

  @Override
  public void close() {
    // Don't close the server here anymore since it's a singleton
    // Just log that we're closing and clear the cache
    logger.info("ReactJSFormatter closed, NodeJsServer stopped");
    resultCache.clear();
  }
}
