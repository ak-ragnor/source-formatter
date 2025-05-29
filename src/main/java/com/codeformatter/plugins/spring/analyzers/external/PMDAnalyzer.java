package com.codeformatter.plugins.spring.analyzers.external;

import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.util.LoggerUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced PMD analyzer with auto-fix support for code quality, bug detection, and performance
 * issues. Integrates PMD to analyze and automatically fix Java code for potential problems.
 */
public class PMDAnalyzer extends ExternalToolAnalyzer {
  private static final Logger logger = LoggerUtil.getLogger(PMDAnalyzer.class);

  // PMD classes for programmatic usage
  private Class<?> pmdClass;
  private Class<?> ruleSetFactoryClass;
  private Class<?> reportClass;
  private boolean pmdLoaded = false;
  private boolean supportsAutoFix = false;

  // PMD rules that can be auto-fixed
  private static final List<String> AUTO_FIXABLE_RULES =
      List.of(
          "UnnecessaryLocalBeforeReturn",
          "SimplifyBooleanReturns",
          "SimplifyBooleanExpressions",
          "CollapsibleIfStatements",
          "UnnecessaryWrapperObjectCreation",
          "BooleanInstantiation",
          "StringInstantiation",
          "EmptyBlock",
          "ConsecutiveLiteralAppends",
          "UseStringBufferLength",
          "AvoidDuplicateLiterals");

  public PMDAnalyzer(FormatterConfig config) {
    super(config, "PMD");
  }

  @Override
  protected boolean checkToolAvailability() {
    // First check if PMD command is available
    String command = findPMDCommand();
    if (command != null) {
      logger.info("PMD command line tool is available: " + command);

      // Check if the command line version supports auto-fix (newer PMD versions)
      supportsAutoFix = checkAutoFixSupport(command);
      if (supportsAutoFix) {
        logger.info("PMD auto-fix is supported");
      } else {
        logger.info("PMD auto-fix is not supported in this version");
      }

      return true;
    }

    // Then try to load PMD classes via reflection
    try {
      pmdClass = Class.forName("net.sourceforge.pmd.PMD");
      ruleSetFactoryClass = Class.forName("net.sourceforge.pmd.RuleSetFactory");
      reportClass = Class.forName("net.sourceforge.pmd.Report");

      pmdLoaded = true;
      logger.info("PMD is available programmatically");

      // Check if programmatic version supports auto-fix
      supportsAutoFix = checkProgrammaticAutoFixSupport();
      return true;

    } catch (ClassNotFoundException e) {
      unavailabilityReason = "PMD not found. Install 'pmd' command or add PMD JAR to classpath.";
      logger.warning("PMD not available: " + unavailabilityReason);
      return false;
    } catch (Exception e) {
      unavailabilityReason = "Error loading PMD: " + e.getMessage();
      logger.log(Level.WARNING, "Error loading PMD", e);
      return false;
    }
  }

  @Override
  protected boolean supportsAutoFix() {
    return supportsAutoFix;
  }

  @Override
  protected List<FormatterError> runExternalTool(Path sourceFile, String sourceCode) {
    // Try command line approach first (more reliable)
    List<FormatterError> errors = runPMDCommandLine(sourceFile, false);

    if (!errors.isEmpty() || !pmdLoaded) {
      return errors;
    }

    // Fallback: if command line didn't work but we have PMD in classpath
    try {
      return runPMDProgrammatic(sourceFile);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Both command line and programmatic PMD failed", e);
      errors.add(
          new FormatterError(
              Severity.ERROR,
              "PMD analysis failed: " + e.getMessage(),
              1,
              1,
              "Install PMD command line tool or add PMD JAR to classpath"));
    }

    return errors;
  }

  @Override
  protected AutoFixResult runExternalToolWithAutoFix(Path sourceFile, String sourceCode) {
    if (!supportsAutoFix) {
      return new AutoFixResult("PMD auto-fix is not supported in this version");
    }

    try {
      // Create a copy of the source file for fixing
      Path fixedFile = Files.createTempFile("pmd-fixed", ".java");
      fixedFile.toFile().deleteOnExit();
      Files.writeString(fixedFile, sourceCode);

      // Try command line auto-fix first
      if (findPMDCommand() != null) {
        return runPMDCommandLineAutoFix(fixedFile, sourceCode);
      } else {
        // Fallback to programmatic auto-fix
        return runPMDProgrammaticAutoFix(fixedFile, sourceCode);
      }

    } catch (Exception e) {
      logger.log(Level.WARNING, "Error during PMD auto-fix", e);
      return new AutoFixResult("Auto-fix failed: " + e.getMessage());
    }
  }

  @Override
  protected String getConfigPrefix() {
    return "pmd";
  }

  /** Check if the PMD command supports auto-fix. */
  private boolean checkAutoFixSupport(String command) {
    try {
      // Try to run PMD with --help to see if auto-fix options are available
      ProcessBuilder pb = new ProcessBuilder(command, "--help");
      Process process = pb.start();

      StringBuilder output = new StringBuilder();
      try (var reader =
          new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append("\n");
        }
      }

      int exitCode = process.waitFor();

      // Check if auto-fix related options are mentioned in the help output
      String helpText = output.toString().toLowerCase();
      return helpText.contains("--fix")
          || helpText.contains("--auto-fix")
          || helpText.contains("transform")
          || helpText.contains("--apply-fixes");

    } catch (Exception e) {
      logger.log(Level.FINE, "Could not check PMD auto-fix support", e);
      return false;
    }
  }

  /** Check if programmatic PMD supports auto-fix. */
  private boolean checkProgrammaticAutoFixSupport() {
    try {
      // Check if PMD has rule transformation capabilities
      Class.forName("net.sourceforge.pmd.lang.java.rule.AbstractJavaRule");
      return true; // If we can load advanced PMD classes, assume some auto-fix capability
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /** Run PMD command line with auto-fix. */
  private AutoFixResult runPMDCommandLineAutoFix(Path sourceFile, String originalCode) {
    try {
      // Get ruleset configuration
      String ruleSet = getToolConfig("ruleSet", "rulesets/java/quickstart.xml");
      String pmdCommand = findPMDCommand();

      // Build command for auto-fix (if supported)
      List<String> command = new ArrayList<>();
      command.add(pmdCommand);
      command.add("check");
      command.add("-d");
      command.add(sourceFile.toString());
      command.add("-R");
      command.add(ruleSet);
      command.add("-f");
      command.add("xml");
      command.add("--no-cache");

      // Add auto-fix flag if supported
      if (supportsAutoFix) {
        command.add("--fix");
      }

      // Execute command
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.redirectErrorStream(true);
      Process process = pb.start();

      StringBuilder output = new StringBuilder();
      try (var reader =
          new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append("\n");
        }
      }

      int exitCode = process.waitFor();

      // Read the potentially fixed content
      String fixedContent = Files.readString(sourceFile);

      // Parse remaining violations
      List<FormatterError> remainingErrors = new ArrayList<>();
      if (exitCode == 0 || exitCode == 4) {
        remainingErrors.addAll(parsePMDXmlOutput(output.toString()));
      }

      // Create refactoring information
      List<Refactoring> refactorings = new ArrayList<>();
      if (!originalCode.equals(fixedContent)) {
        refactorings.add(
            new Refactoring(
                "PMD_AUTO_FIX",
                1,
                originalCode.split("\n").length,
                "Applied PMD auto-fixes for code quality issues"));
      }

      return new AutoFixResult(originalCode, fixedContent, refactorings, remainingErrors);

    } catch (Exception e) {
      logger.log(Level.WARNING, "Error during PMD command line auto-fix", e);
      return new AutoFixResult("Command line auto-fix failed: " + e.getMessage());
    }
  }

  /** Run PMD programmatic auto-fix using simple text-based transformations. */
  private AutoFixResult runPMDProgrammaticAutoFix(Path sourceFile, String originalCode) {
    try {
      String fixedContent = originalCode;
      List<Refactoring> refactorings = new ArrayList<>();

      // Apply simple auto-fixes for common PMD rules
      String afterFix = applySimpleAutoFixes(fixedContent);

      if (!afterFix.equals(fixedContent)) {
        fixedContent = afterFix;
        refactorings.add(
            new Refactoring(
                "PMD_SIMPLE_AUTO_FIX",
                1,
                originalCode.split("\n").length,
                "Applied simple auto-fixes for common PMD violations"));
      }

      // Write the fixed content back to the file
      Files.writeString(sourceFile, fixedContent);

      // Run PMD analysis on the fixed code to get remaining errors
      List<FormatterError> remainingErrors = runPMDProgrammatic(sourceFile);

      return new AutoFixResult(originalCode, fixedContent, refactorings, remainingErrors);

    } catch (Exception e) {
      logger.log(Level.WARNING, "Error during PMD programmatic auto-fix", e);
      return new AutoFixResult("Programmatic auto-fix failed: " + e.getMessage());
    }
  }

  /** Apply simple text-based auto-fixes for common PMD violations. */
  private String applySimpleAutoFixes(String sourceCode) {
    String result = sourceCode;

    // Fix UnnecessaryLocalBeforeReturn
    result = fixUnnecessaryLocalBeforeReturn(result);

    // Fix SimplifyBooleanReturns
    result = fixSimplifyBooleanReturns(result);

    // Fix BooleanInstantiation
    result = fixBooleanInstantiation(result);

    // Fix StringInstantiation
    result = fixStringInstantiation(result);

    // Fix UnnecessaryWrapperObjectCreation
    result = fixUnnecessaryWrapperObjectCreation(result);

    // Fix EmptyBlock
    result = fixEmptyBlocks(result);

    return result;
  }

  /** Fix UnnecessaryLocalBeforeReturn pattern. */
  private String fixUnnecessaryLocalBeforeReturn(String code) {
    // Pattern: Type var = expression; return var;
    Pattern pattern =
        Pattern.compile(
            "(\\s+)(\\w+(?:<[^>]+>)?\\s+)(\\w+)\\s*=\\s*([^;]+);\\s*\\n\\s*return\\s+\\3\\s*;",
            Pattern.MULTILINE);

    Matcher matcher = pattern.matcher(code);
    StringBuffer result = new StringBuffer();

    while (matcher.find()) {
      String indent = matcher.group(1);
      String expression = matcher.group(4);
      matcher.appendReplacement(result, indent + "return " + expression + ";");
    }
    matcher.appendTail(result);

    return result.toString();
  }

  /** Fix SimplifyBooleanReturns pattern. */
  private String fixSimplifyBooleanReturns(String code) {
    // Pattern: if (condition) return true; else return false;
    Pattern pattern =
        Pattern.compile(
            "if\\s*\\(([^)]+)\\)\\s*\\{?\\s*return\\s+true\\s*;\\s*\\}?\\s*else\\s*\\{?\\s*return\\s+false\\s*;\\s*\\}?",
            Pattern.MULTILINE);

    Matcher matcher = pattern.matcher(code);
    StringBuffer result = new StringBuffer();

    while (matcher.find()) {
      String condition = matcher.group(1);
      matcher.appendReplacement(result, "return " + condition + ";");
    }
    matcher.appendTail(result);

    return result.toString();
  }

  /** Fix BooleanInstantiation pattern. */
  private String fixBooleanInstantiation(String code) {
    // Pattern: new Boolean(true/false)
    Pattern pattern = Pattern.compile("new\\s+Boolean\\s*\\(\\s*(true|false)\\s*\\)");

    Matcher matcher = pattern.matcher(code);
    StringBuffer result = new StringBuffer();

    while (matcher.find()) {
      String value = matcher.group(1);
      matcher.appendReplacement(result, "Boolean." + value.toUpperCase());
    }
    matcher.appendTail(result);

    return result.toString();
  }

  /** Fix StringInstantiation pattern. */
  private String fixStringInstantiation(String code) {
    // Pattern: new String("literal")
    Pattern pattern = Pattern.compile("new\\s+String\\s*\\(\\s*\"([^\"]*?)\"\\s*\\)");

    Matcher matcher = pattern.matcher(code);
    StringBuffer result = new StringBuffer();

    while (matcher.find()) {
      String literal = matcher.group(1);
      matcher.appendReplacement(result, "\"" + literal + "\"");
    }
    matcher.appendTail(result);

    return result.toString();
  }

  /** Fix UnnecessaryWrapperObjectCreation pattern. */
  private String fixUnnecessaryWrapperObjectCreation(String code) {
    // Pattern: new Integer(value) -> Integer.valueOf(value)
    Pattern pattern =
        Pattern.compile("new\\s+(Integer|Long|Double|Float|Short|Byte)\\s*\\(([^)]+)\\)");

    Matcher matcher = pattern.matcher(code);
    StringBuffer result = new StringBuffer();

    while (matcher.find()) {
      String type = matcher.group(1);
      String value = matcher.group(2);
      matcher.appendReplacement(result, type + ".valueOf(" + value + ")");
    }
    matcher.appendTail(result);

    return result.toString();
  }

  /** Fix EmptyBlock pattern. */
  private String fixEmptyBlocks(String code) {
    // Pattern: { } or {\n\s*}
    Pattern pattern = Pattern.compile("\\{\\s*\\}");

    Matcher matcher = pattern.matcher(code);
    StringBuffer result = new StringBuffer();

    while (matcher.find()) {
      // Replace empty block with a comment
      matcher.appendReplacement(result, "{ /* TODO: implement */ }");
    }
    matcher.appendTail(result);

    return result.toString();
  }

  /** Run PMD via command line and parse output. */
  private List<FormatterError> runPMDCommandLine(Path sourceFile, boolean autoFix) {
    List<FormatterError> errors = new ArrayList<>();

    try {
      // Find PMD command or script
      String pmdCommand = findPMDCommand();
      if (pmdCommand == null) {
        return errors; // No command available
      }

      // Get ruleset configuration
      String ruleSet = getToolConfig("ruleSet", "rulesets/java/quickstart.xml");

      // Build command
      List<String> command = new ArrayList<>();
      command.add(pmdCommand);
      command.add("check");
      command.add("-d");
      command.add(sourceFile.toString());
      command.add("-R");
      command.add(ruleSet);
      command.add("-f");
      command.add("xml");
      command.add("--no-cache");

      // Execute command
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.redirectErrorStream(true);
      Process process = pb.start();

      // Read output
      StringBuilder output = new StringBuilder();
      try (var reader =
          new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append("\n");
        }
      }

      int exitCode = process.waitFor();

      // Parse XML output (PMD returns 4 when violations found)
      if (exitCode == 0 || exitCode == 4) {
        errors.addAll(parsePMDXmlOutput(output.toString()));
      } else {
        logger.warning("PMD command failed with exit code: " + exitCode);
        logger.warning("Output: " + output.toString());
      }

    } catch (Exception e) {
      logger.log(Level.WARNING, "Error running PMD command", e);
    }

    return errors;
  }

  /** Fallback method to run PMD programmatically. */
  private List<FormatterError> runPMDProgrammatic(Path sourceFile) throws Exception {
    List<FormatterError> errors = new ArrayList<>();

    // This is a simplified approach - in practice, PMD programmatic API is complex
    // For now, we'll add a placeholder that suggests using command line
    errors.add(
        new FormatterError(
            Severity.INFO,
            "PMD programmatic analysis not fully implemented",
            1,
            1,
            "Install PMD command line tool for full analysis capabilities"));

    return errors;
  }

  /** Find PMD command or script. */
  private String findPMDCommand() {
    // Try common locations for PMD
    String[] possibleCommands = {
      "pmd",
      "/usr/local/bin/pmd",
      "/opt/pmd/bin/pmd",
      System.getProperty("user.home") + "/.local/bin/pmd",
      System.getProperty("user.home") + "/pmd/bin/pmd"
    };

    for (String cmd : possibleCommands) {
      try {
        Process process = new ProcessBuilder(cmd, "--version").start();
        int exitCode = process.waitFor();
        if (exitCode == 0) {
          return cmd;
        }
      } catch (Exception e) {
        // Try next command
      }
    }

    // Try PMD batch files on Windows
    if (System.getProperty("os.name").toLowerCase().contains("windows")) {
      String[] windowsCommands = {"pmd.bat", "C:\\pmd\\bin\\pmd.bat"};

      for (String cmd : windowsCommands) {
        try {
          Process process = new ProcessBuilder(cmd, "--version").start();
          int exitCode = process.waitFor();
          if (exitCode == 0) {
            return cmd;
          }
        } catch (Exception e) {
          // Try next command
        }
      }
    }

    return null;
  }

  /** Parse PMD XML output to extract violations. */
  private List<FormatterError> parsePMDXmlOutput(String xmlOutput) {
    List<FormatterError> errors = new ArrayList<>();

    try {
      // Simple XML parsing for PMD output
      String[] lines = xmlOutput.split("\n");
      for (String line : lines) {
        if (line.trim().startsWith("<violation ")) {
          FormatterError error = parsePMDViolationLine(line);
          if (error != null) {
            errors.add(error);
          }
        }
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error parsing PMD XML output", e);
    }

    return errors;
  }

  /** Parse a single PMD violation line from XML. */
  private FormatterError parsePMDViolationLine(String line) {
    try {
      // Extract attributes using simple regex
      int beginLine = extractIntAttribute(line, "beginline", 1);
      int endLine = extractIntAttribute(line, "endline", beginLine);
      int beginColumn = extractIntAttribute(line, "begincolumn", 1);
      String priority = extractStringAttribute(line, "priority", "3");
      String rule = extractStringAttribute(line, "rule", "PMD Rule");
      String ruleset = extractStringAttribute(line, "ruleset", "");
      String externalInfoUrl = extractStringAttribute(line, "externalInfoUrl", "");

      // Extract violation text (between tags)
      String message = extractViolationText(line);

      // Convert priority to severity
      Severity severity = convertPMDPriority(priority);

      // Create suggestion based on rule
      String suggestion = createPMDSuggestion(rule, message, externalInfoUrl);

      return new FormatterError(
          severity, "PMD [" + rule + "]: " + message, beginLine, beginColumn, suggestion);
    } catch (Exception e) {
      logger.log(Level.FINE, "Error parsing PMD violation line: " + line, e);
      return null;
    }
  }

  private String extractViolationText(String line) {
    try {
      int start = line.indexOf('>');
      int end = line.lastIndexOf('<');
      if (start != -1 && end != -1 && start < end) {
        return line.substring(start + 1, end).trim();
      }
    } catch (Exception e) {
      // Fall back to empty message
    }
    return "PMD violation detected";
  }

  private int extractIntAttribute(String xml, String attrName, int defaultValue) {
    try {
      String pattern = attrName + "=\"([^\"]+)\"";
      java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
      java.util.regex.Matcher m = p.matcher(xml);
      if (m.find()) {
        return Integer.parseInt(m.group(1));
      }
    } catch (Exception e) {
      // Use default
    }
    return defaultValue;
  }

  private String extractStringAttribute(String xml, String attrName, String defaultValue) {
    try {
      String pattern = attrName + "=\"([^\"]+)\"";
      java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
      java.util.regex.Matcher m = p.matcher(xml);
      if (m.find()) {
        return m.group(1);
      }
    } catch (Exception e) {
      // Use default
    }
    return defaultValue;
  }

  private Severity convertPMDPriority(String priority) {
    try {
      int prio = Integer.parseInt(priority);
      return switch (prio) {
        case 1, 2 -> Severity.ERROR; // High priority
        case 3 -> Severity.WARNING; // Medium priority
        case 4, 5 -> Severity.INFO; // Low priority
        default -> Severity.WARNING;
      };
    } catch (NumberFormatException e) {
      return Severity.WARNING;
    }
  }

  private String createPMDSuggestion(String rule, String message, String externalInfoUrl) {
    // Create specific suggestions based on PMD rules
    switch (rule) {
      case "UnusedLocalVariable":
        return "Remove this unused local variable or use it in your code.";
      case "UnusedPrivateField":
        return "Remove this unused private field or use it in your class.";
      case "UnusedFormalParameter":
        return "Remove this unused parameter or use it in the method. Consider using @SuppressWarnings(\"unused\") if intentionally unused.";
      case "EmptyBlock":
        return "Remove this empty block or add meaningful code inside it.";
      case "UnnecessaryLocalBeforeReturn":
        return "Return the value directly instead of storing it in a local variable first.";
      case "SimplifyBooleanReturns":
        return "Simplify this boolean return statement by returning the boolean expression directly.";
      case "CollapsibleIfStatements":
        return "Combine these nested if statements into a single if statement with && operator.";
      case "UseStringBufferLength":
        return "Use StringBuffer.length() == 0 instead of StringBuffer.toString().equals(\"\").";
      case "AvoidDeeplyNestedIfStmts":
        return "Refactor this code to reduce nesting depth. Consider extracting methods or using early returns.";
      case "CyclomaticComplexity":
        return "Reduce the complexity of this method by extracting smaller methods or simplifying the logic.";
      case "ExcessiveMethodLength":
        return "Break this long method into smaller, more focused methods.";
      case "ExcessiveParameterList":
        return "Reduce the number of parameters by using a parameter object or builder pattern.";
      case "MethodArgumentCouldBeFinal":
        return "Make this method parameter final to prevent accidental reassignment.";
      case "LocalVariableCouldBeFinal":
        return "Make this local variable final since it's never reassigned.";
      case "AvoidReassigningParameters":
        return "Don't reassign method parameters. Create a local variable instead.";
      case "UseEqualsToCompareStrings":
        return "Use .equals() method to compare strings instead of == operator.";
      case "AvoidDuplicateLiterals":
        return "Extract this duplicate string literal into a constant.";
      case "StringInstantiation":
        return "Don't use 'new String()' - use string literals directly.";
      case "InefficientStringBuffering":
        return "Use StringBuilder for efficient string concatenation instead of + operator in loops.";
      case "ConsecutiveLiteralAppends":
        return "Combine these consecutive string appends into a single append call.";
      case "UnnecessaryWrapperObjectCreation":
        return "Use valueOf() method instead of constructor for wrapper object creation.";
      case "BooleanInstantiation":
        return "Use Boolean.TRUE/Boolean.FALSE or Boolean.valueOf() instead of new Boolean().";
      default:
        // Generic suggestion
        if (externalInfoUrl != null && !externalInfoUrl.isEmpty()) {
          return "Fix this " + rule + " violation. See: " + externalInfoUrl;
        } else {
          return "Fix this " + rule + " violation according to PMD best practices.";
        }
    }
  }
}
