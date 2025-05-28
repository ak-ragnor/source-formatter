package com.codeformatter.plugins.spring.analyzers.external;

import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.util.LoggerUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PMD analyzer for code quality, bug detection, and performance issues. Integrates PMD to analyze
 * Java code for potential problems.
 */
public class PMDAnalyzer extends ExternalToolAnalyzer {
  private static final Logger logger = LoggerUtil.getLogger(PMDAnalyzer.class);

  // PMD classes for programmatic usage
  private Class<?> pmdClass;
  private Class<?> ruleSetFactoryClass;
  private Class<?> reportClass;
  private boolean pmdLoaded = false;

  public PMDAnalyzer(FormatterConfig config) {
    super(config, "PMD");
  }

  @Override
  protected boolean checkToolAvailability() {
    // First check if PMD command is available
    String command = findPMDCommand();
    if (command != null) {
      logger.info("PMD command line tool is available: " + command);
      return true;
    }

    // Then try to load PMD classes via reflection
    try {
      pmdClass = Class.forName("net.sourceforge.pmd.PMD");
      ruleSetFactoryClass = Class.forName("net.sourceforge.pmd.RuleSetFactory");
      reportClass = Class.forName("net.sourceforge.pmd.Report");

      pmdLoaded = true;
      logger.info("PMD is available programmatically");
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
  protected List<FormatterError> runExternalTool(Path sourceFile, String sourceCode) {
    // Try command line approach first (more reliable)
    List<FormatterError> errors = runPMDCommandLine(sourceFile);

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
  protected String getConfigPrefix() {
    return "pmd";
  }

  /** Run PMD via command line and parse output. */
  private List<FormatterError> runPMDCommandLine(Path sourceFile) {
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

  /** Create a default PMD ruleset configuration. */
  public Path createDefaultPMDRuleset() throws IOException {
    Path tempRuleset = Files.createTempFile("pmd-rules", ".xml");
    tempRuleset.toFile().deleteOnExit();

    // Create a comprehensive ruleset based on our configuration
    String rulesetContent = generatePMDRuleset();
    Files.writeString(tempRuleset, rulesetContent);

    logger.fine("Created default PMD ruleset at: " + tempRuleset);
    return tempRuleset;
  }

  /** Generate PMD XML ruleset content. */
  private String generatePMDRuleset() {
    int maxMethodLines = config.getPluginConfig("spring", "maxMethodLines", 50);
    int maxMethodComplexity = config.getPluginConfig("spring", "maxMethodComplexity", 15);

    return String.format(
        """
            <?xml version="1.0"?>
            <ruleset name="Advanced Formatter PMD Rules"
                     xmlns="http://pmd.sourceforge.net/ruleset/2.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://pmd.sourceforge.net/ruleset/2.0.0
                     https://pmd.sourceforge.io/ruleset_2_0_0.xsd">

                <description>PMD rules for the Advanced Code Formatter</description>

                <!-- Best Practices -->
                <rule ref="category/java/bestpractices.xml/UnusedLocalVariable"/>
                <rule ref="category/java/bestpractices.xml/UnusedPrivateField"/>
                <rule ref="category/java/bestpractices.xml/UnusedFormalParameter"/>
                <rule ref="category/java/bestpractices.xml/AvoidReassigningParameters"/>
                <rule ref="category/java/bestpractices.xml/UseCollectionIsEmpty"/>
                <rule ref="category/java/bestpractices.xml/UseStringBufferLength"/>
                <rule ref="category/java/bestpractices.xml/MethodArgumentCouldBeFinal"/>
                <rule ref="category/java/bestpractices.xml/LocalVariableCouldBeFinal"/>

                <!-- Code Style -->
                <rule ref="category/java/codestyle.xml/UnnecessaryLocalBeforeReturn"/>
                <rule ref="category/java/codestyle.xml/EmptyBlock"/>
                <rule ref="category/java/codestyle.xml/UnnecessaryConstructor"/>
                <rule ref="category/java/codestyle.xml/CollapsibleIfStatements"/>
                <rule ref="category/java/codestyle.xml/SimplifyBooleanReturns"/>
                <rule ref="category/java/codestyle.xml/BooleanGetMethodName"/>

                <!-- Design -->
                <rule ref="category/java/design.xml/ExcessiveMethodLength">
                    <properties>
                        <property name="minimum" value="%d"/>
                    </properties>
                </rule>
                <rule ref="category/java/design.xml/ExcessiveParameterList">
                    <properties>
                        <property name="minimum" value="7"/>
                    </properties>
                </rule>
                <rule ref="category/java/design.xml/CyclomaticComplexity">
                    <properties>
                        <property name="methodReportLevel" value="%d"/>
                    </properties>
                </rule>
                <rule ref="category/java/design.xml/AvoidDeeplyNestedIfStmts">
                    <properties>
                        <property name="problemDepth" value="3"/>
                    </properties>
                </rule>
                <rule ref="category/java/design.xml/SimplifyBooleanExpressions"/>
                <rule ref="category/java/design.xml/SwitchStmtsShouldHaveDefault"/>

                <!-- Error Prone -->
                <rule ref="category/java/errorprone.xml/EmptyBlock"/>
                <rule ref="category/java/errorprone.xml/UseEqualsToCompareStrings"/>
                <rule ref="category/java/errorprone.xml/AvoidDuplicateLiterals">
                    <properties>
                        <property name="skipAnnotations" value="true"/>
                        <property name="minimumLength" value="3"/>
                        <property name="minimumOccurrences" value="3"/>
                    </properties>
                </rule>
                <rule ref="category/java/errorprone.xml/StringInstantiation"/>
                <rule ref="category/java/errorprone.xml/UnnecessaryWrapperObjectCreation"/>
                <rule ref="category/java/errorprone.xml/BooleanInstantiation"/>

                <!-- Performance -->
                <rule ref="category/java/performance.xml/InefficientStringBuffering"/>
                <rule ref="category/java/performance.xml/ConsecutiveLiteralAppends"/>
                <rule ref="category/java/performance.xml/UseStringBufferLength"/>
                <rule ref="category/java/performance.xml/OptimizableToArrayCall"/>

            </ruleset>
            """,
        maxMethodLines, maxMethodComplexity);
  }
}
