package com.codeformatter.plugins.react;

import com.codeformatter.api.FormatterPlugin;
import com.codeformatter.api.FormatterResult;
import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.util.LoggerUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * React JS formatter plugin using ESLint with Prettier plugin.
 *
 * <p>This implementation uses a single NodeJsServer endpoint for both formatting and analysis,
 * leveraging ESLint's built-in suggestions for more accurate and helpful guidance to users.
 */
public class ReactJSFormatter implements FormatterPlugin, AutoCloseable {
  private static final Logger logger = LoggerUtil.getLogger(ReactJSFormatter.class);

  private FormatterConfig config;
  private NodeJsServer server;
  private final Map<String, FormatterResult> resultCache = new ConcurrentHashMap<>();
  private boolean nodeJsAvailable = false;
  private boolean disabled = false;
  private String disabledReason = null;
  private final ObjectMapper objectMapper = new ObjectMapper();

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
        logger.info("ReactJSFormatter initialized with ESLint+Prettier integration");
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

    // Create Prettier options
    Map<String, Object> prettierOptions = new HashMap<>();
    prettierOptions.put("printWidth", config.getGeneralConfig("lineLength", 100));
    prettierOptions.put("tabWidth", config.getGeneralConfig("indentSize", 2));
    prettierOptions.put("useTabs", config.getGeneralConfig("useTabs", false));
    prettierOptions.put("singleQuote", true);
    prettierOptions.put("jsxBracketSameLine", false);

    options.put("prettier", prettierOptions);

    // Create ESLint options
    Map<String, Object> eslintOptions = new HashMap<>();
    Map<String, Object> eslintRules = new HashMap<>();

    // Add any custom ESLint rules from config
    Map<String, Object> reactConfig = new HashMap<>();
    if (config.getPluginConfigsMap().containsKey("react")) {
      reactConfig.putAll(config.getPluginConfigsMap().get("react"));
    }

    // Set up rules for React hooks if enabled
    boolean enforceHookDependencies =
        (boolean) reactConfig.getOrDefault("enforceHookDependencies", true);
    if (enforceHookDependencies) {
      eslintRules.put("react-hooks/exhaustive-deps", "warn");
    } else {
      eslintRules.put("react-hooks/exhaustive-deps", "off");
    }

    eslintOptions.put("rules", eslintRules);
    options.put("eslint", eslintOptions);

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
              "Install Node.js and required npm packages (eslint, eslint-plugin-prettier, eslint-plugin-react, eslint-plugin-react-hooks)");

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
    boolean processingSucceeded = false;

    try {
      boolean isReact = isReactFile(filePath, sourceCode);
      logger.fine("Processing " + filePath + " (React: " + isReact + ")");

      // Use the combined format-and-analyze endpoint
      JsonNode result = formatAndAnalyze(sourceCode, isReact, contentHash);

      if (result.has("success") && result.get("success").asBoolean()) {
        processingSucceeded = true;

        // Get formatted code
        if (result.has("formattedCode")) {
          formattedCode = result.get("formattedCode").asText();
        }

        // Process issues
        if (result.has("issues") && result.get("issues").isArray()) {
          int fixableIssueCount = 0;

          for (JsonNode issue : result.get("issues")) {
            Severity severity = Severity.INFO;
            if (issue.has("severity")) {
              String severityStr = issue.get("severity").asText();
              severity =
                  "error".equals(severityStr)
                      ? Severity.ERROR
                      : "warning".equals(severityStr) ? Severity.WARNING : Severity.INFO;
            }

            String message = issue.has("message") ? issue.get("message").asText() : "";
            int line = issue.has("line") ? issue.get("line").asInt() : 1;
            int column = issue.has("column") ? issue.get("column").asInt() : 1;

            // Use ESLint's built-in suggestion if available
            String suggestion =
                issue.has("suggestion") && !issue.get("suggestion").isNull()
                    ? issue.get("suggestion").asText()
                    : null;

            // Track if the issue is fixable
            boolean fixable = issue.has("fixable") && issue.get("fixable").asBoolean();
            if (fixable) {
              fixableIssueCount++;
              if (suggestion == null) {
                suggestion = "This issue can be automatically fixed by ESLint.";
              }
            }

            FormatterError error = new FormatterError(severity, message, line, column, suggestion);
            errors.add(error);
          }

          // Add fixable issues count to refactorings if any were found
          if (fixableIssueCount > 0) {
            refactorings.add(
                new Refactoring(
                    "AUTO_FIXABLE",
                    1,
                    1,
                    fixableIssueCount + " issues can be automatically fixed by ESLint"));
          }
        }

        // If formatting changed the code, add a refactoring
        if (!formattedCode.equals(sourceCode)) {
          refactorings.add(
              new Refactoring(
                  "FORMATTING",
                  1,
                  countLines(sourceCode),
                  "Applied "
                      + (isReact ? "React" : "JavaScript")
                      + " formatting with ESLint+Prettier"));
        }
      } else if (result.has("error")) {
        errors.add(
            new FormatterError(
                Severity.WARNING,
                "Error processing with ESLint+Prettier: " + result.get("error").asText(),
                1,
                1,
                "Check that all required npm packages are installed correctly"));

        // Use original code if formatting failed
        formattedCode = sourceCode;
      }
    } catch (IOException e) {
      logger.log(Level.WARNING, "Error processing " + filePath + ": " + e.getMessage(), e);

      errors.add(
          new FormatterError(
              Severity.WARNING,
              "Error processing with Node.js: " + e.getMessage(),
              1,
              1,
              "Make sure Node.js is installed and required npm packages are available: eslint, eslint-plugin-prettier, eslint-plugin-react"));

      // Use original code if formatting failed
      formattedCode = sourceCode;
    }

    // Determine if formatting was successful
    // Format is successful if there are no FATAL errors, even if there are regular errors or
    // warnings
    boolean successful =
        processingSucceeded && errors.stream().noneMatch(e -> e.getSeverity() == Severity.FATAL);

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

  /** Format and analyze code in a single operation using the new combined endpoint. */
  private JsonNode formatAndAnalyze(String sourceCode, boolean isReact, String cacheKey)
      throws IOException {
    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("code", sourceCode);
    requestBody.put("isReact", isReact);
    requestBody.put("cacheKey", cacheKey);

    String responseJson =
        server.callEndpoint("/format-and-analyze", objectMapper.writeValueAsString(requestBody));
    return objectMapper.readTree(responseJson);
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
    logger.info("ReactJSFormatter closed");
    resultCache.clear();
  }
}
