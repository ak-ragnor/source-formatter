package com.codeformatter.plugins.spring.analyzers.external;

import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.util.LoggerUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Enhanced Checkstyle analyzer with auto-fix support for coding standards and style consistency.
 * Integrates Checkstyle programmatically to analyze and automatically fix Java code issues.
 */
public class CheckstyleAnalyzer extends ExternalToolAnalyzer {
  private static final Logger logger = LoggerUtil.getLogger(CheckstyleAnalyzer.class);

  // We'll use Checkstyle programmatically via reflection to avoid hard dependency
  private Class<?> checkerClass;
  private Class<?> configurationClass;
  private Class<?> auditListenerClass;
  private Object checkerInstance;
  private boolean checkstyleLoaded = false;
  private boolean supportsAutoFix = false;

  public CheckstyleAnalyzer(FormatterConfig config) {
    super(config, "Checkstyle");
  }

  @Override
  protected boolean checkToolAvailability() {
    // First check if Checkstyle command is available
    String command = findCheckstyleCommand();
    if (command != null) {
      logger.info("Checkstyle command line tool is available: " + command);

      // Check if the command line version supports auto-fix
      supportsAutoFix = checkAutoFixSupport(command);
      if (supportsAutoFix) {
        logger.info("Checkstyle auto-fix is supported");
      } else {
        logger.info("Checkstyle auto-fix is not supported in this version");
      }

      return true;
    }

    // Then try to load Checkstyle classes via reflection
    try {
      checkerClass = Class.forName("com.puppycrawl.tools.checkstyle.Checker");
      configurationClass = Class.forName("com.puppycrawl.tools.checkstyle.api.Configuration");
      auditListenerClass = Class.forName("com.puppycrawl.tools.checkstyle.api.AuditListener");

      checkstyleLoaded = true;
      logger.info("Checkstyle is available programmatically");

      // Programmatic version has limited auto-fix support
      supportsAutoFix = false;
      return true;

    } catch (ClassNotFoundException e) {
      unavailabilityReason =
          "Checkstyle not found. Install 'checkstyle' command or add checkstyle JAR to classpath.";
      logger.warning("Checkstyle not available: " + unavailabilityReason);
      return false;
    } catch (Exception e) {
      unavailabilityReason = "Error loading Checkstyle: " + e.getMessage();
      logger.log(Level.WARNING, "Error loading Checkstyle", e);
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
    List<FormatterError> errors = runCheckstyleCommandLine(sourceFile, false);

    if (!errors.isEmpty() || !checkstyleLoaded) {
      return errors;
    }

    // Fallback: if command line didn't work but we have Checkstyle in classpath
    try {
      return runCheckstyleProgrammatic(sourceFile);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Both command line and programmatic Checkstyle failed", e);
      errors.add(
          new FormatterError(
              Severity.ERROR,
              "Checkstyle analysis failed: " + e.getMessage(),
              1,
              1,
              "Install Checkstyle command line tool or add Checkstyle JAR to classpath"));
    }

    return errors;
  }

  @Override
  protected AutoFixResult runExternalToolWithAutoFix(Path sourceFile, String sourceCode) {
    if (!supportsAutoFix) {
      return new AutoFixResult("Checkstyle auto-fix is not supported in this version");
    }

    try {
      // Create a copy of the source file for fixing
      Path fixedFile = Files.createTempFile("checkstyle-fixed", ".java");
      fixedFile.toFile().deleteOnExit();
      Files.writeString(fixedFile, sourceCode);

      // Run Checkstyle with auto-fix
      List<FormatterError> remainingErrors = runCheckstyleCommandLine(fixedFile, true);

      // Read the potentially fixed content
      String fixedContent = Files.readString(fixedFile);

      // Create refactoring information
      List<Refactoring> refactorings = new ArrayList<>();
      if (!sourceCode.equals(fixedContent)) {
        refactorings.add(
            new Refactoring(
                "CHECKSTYLE_AUTO_FIX",
                1,
                sourceCode.split("\n").length,
                "Applied Checkstyle auto-fixes for code style violations"));
      }

      return new AutoFixResult(sourceCode, fixedContent, refactorings, remainingErrors);

    } catch (Exception e) {
      logger.log(Level.WARNING, "Error during Checkstyle auto-fix", e);
      return new AutoFixResult("Auto-fix failed: " + e.getMessage());
    }
  }

  @Override
  protected String getConfigPrefix() {
    return "checkstyle";
  }

  /** Check if the Checkstyle command supports auto-fix. */
  private boolean checkAutoFixSupport(String command) {
    try {
      // Try to run checkstyle with --help to see if --fix option is available
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

      // Check if --fix option is mentioned in the help output
      return output.toString().toLowerCase().contains("--fix")
          || output.toString().toLowerCase().contains("-fix");

    } catch (Exception e) {
      logger.log(Level.FINE, "Could not check Checkstyle auto-fix support", e);
      return false;
    }
  }

  /** Fallback method to run Checkstyle programmatically. */
  private List<FormatterError> runCheckstyleProgrammatic(Path sourceFile) throws Exception {
    List<FormatterError> errors = new ArrayList<>();

    // Create a simplified checker using reflection
    Object checker = checkerClass.getDeclaredConstructor().newInstance();

    // Create configuration
    Path configFile = createDefaultCheckstyleConfig();
    Class<?> configLoaderClass =
        Class.forName("com.puppycrawl.tools.checkstyle.ConfigurationLoader");
    Object configuration =
        configLoaderClass
            .getMethod(
                "loadConfiguration",
                String.class,
                Class.forName("com.puppycrawl.tools.checkstyle.PropertyResolver"))
            .invoke(null, configFile.toString(), null);

    // Set up a simple audit listener using dynamic proxy
    Object auditListener =
        java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {auditListenerClass},
            (proxy, method, args) -> {
              if ("addError".equals(method.getName()) && args.length >= 1) {
                try {
                  Object auditEvent = args[0];
                  Class<?> auditEventClass = auditEvent.getClass();

                  int line = (Integer) auditEventClass.getMethod("getLine").invoke(auditEvent);
                  int column = (Integer) auditEventClass.getMethod("getColumn").invoke(auditEvent);
                  String message =
                      (String) auditEventClass.getMethod("getMessage").invoke(auditEvent);

                  FormatterError error =
                      new FormatterError(
                          Severity.WARNING,
                          "Checkstyle: " + message,
                          line > 0 ? line : 1,
                          column > 0 ? column : 1,
                          createSuggestion(message, ""));

                  errors.add(error);
                } catch (Exception e) {
                  logger.log(Level.FINE, "Error processing audit event", e);
                }
              }
              return null;
            });

    checkerClass.getMethod("addAuditListener", auditListenerClass).invoke(checker, auditListener);

    // Configure and run
    checkerClass.getMethod("configure", configurationClass).invoke(checker, configuration);

    List<java.io.File> files = List.of(sourceFile.toFile());
    checkerClass.getMethod("process", List.class).invoke(checker, files);

    // Clean up
    checkerClass.getMethod("destroy").invoke(checker);

    return errors;
  }

  /** Create Checkstyle configuration from our config or use default. */
  private Object createCheckstyleConfiguration() throws Exception {
    // Try to find custom checkstyle config file
    String configFile = getToolConfig("configFile", null);
    Path configPath = null;

    if (configFile != null) {
      configPath = Paths.get(configFile);
      if (!Files.exists(configPath)) {
        logger.warning("Custom Checkstyle config file not found: " + configFile);
        configPath = null;
      }
    }

    // If no custom config, create one programmatically or use built-in
    if (configPath == null) {
      configPath = createDefaultCheckstyleConfig();
    }

    // Load configuration
    Class<?> configLoaderClass =
        Class.forName("com.puppycrawl.tools.checkstyle.ConfigurationLoader");
    Object configuration =
        configLoaderClass
            .getMethod(
                "loadConfiguration",
                String.class,
                Class.forName("com.puppycrawl.tools.checkstyle.PropertyResolver"))
            .invoke(null, configPath.toString(), null);

    return configuration;
  }

  /** Create a default Checkstyle configuration based on our formatter config. */
  private Path createDefaultCheckstyleConfig() throws IOException {
    Path tempConfig = Files.createTempFile("checkstyle-config", ".xml");
    tempConfig.toFile().deleteOnExit();

    // Build Checkstyle configuration based on our formatter settings
    int indentSize = config.getGeneralConfig("indentSize", 4);
    int lineLength = config.getGeneralConfig("lineLength", 100);
    boolean allowTabs = config.getGeneralConfig("useTabs", false);

    String configContent = generateCheckstyleConfig(indentSize, lineLength, allowTabs);
    Files.writeString(tempConfig, configContent);

    logger.fine("Created default Checkstyle config at: " + tempConfig);
    return tempConfig;
  }

  /** Generate Checkstyle XML configuration content with auto-fixable rules. */
  private String generateCheckstyleConfig(int indentSize, int lineLength, boolean allowTabs) {
    return """
            <?xml version="1.0"?>
            <!DOCTYPE module PUBLIC
                "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
                "https://checkstyle.org/dtds/configuration_1_3.dtd">

            <module name="Checker">
                <property name="charset" value="UTF-8"/>
                <property name="severity" value="warning"/>
                <property name="fileExtensions" value="java"/>

                <!-- Checks for whitespace -->
                <module name="FileTabCharacter">
                    <property name="eachLine" value="true"/>
                    <property name="fileExtensions" value="java"/>
                </module>

                <module name="TreeWalker">
                    <!-- AUTO-FIXABLE: Naming Conventions -->
                    <module name="ConstantName"/>
                    <module name="LocalFinalVariableName"/>
                    <module name="LocalVariableName"/>
                    <module name="MemberName"/>
                    <module name="MethodName"/>
                    <module name="PackageName"/>
                    <module name="ParameterName"/>
                    <module name="StaticVariableName"/>
                    <module name="TypeName"/>

                    <!-- AUTO-FIXABLE: Imports -->
                    <module name="AvoidStarImport"/>
                    <module name="IllegalImport"/>
                    <module name="RedundantImport"/>
                    <module name="UnusedImports"/>
                    <module name="ImportOrder">
                        <property name="groups" value="/^java\\./,javax,org,com"/>
                        <property name="ordered" value="true"/>
                        <property name="separated" value="true"/>
                    </module>

                    <!-- Size Violations -->
                    <module name="LineLength">
                        <property name="max" value="%d"/>
                        <property name="ignorePattern" value="^package.*|^import.*|a href|href|http://|https://|ftp://"/>
                    </module>
                    <module name="MethodLength">
                        <property name="max" value="50"/>
                    </module>
                    <module name="ParameterNumber">
                        <property name="max" value="7"/>
                    </module>

                    <!-- AUTO-FIXABLE: Whitespace -->
                    <module name="EmptyForIteratorPad"/>
                    <module name="GenericWhitespace"/>
                    <module name="MethodParamPad"/>
                    <module name="NoWhitespaceAfter"/>
                    <module name="NoWhitespaceBefore"/>
                    <module name="OperatorWrap"/>
                    <module name="ParenPad"/>
                    <module name="TypecastParenPad"/>
                    <module name="WhitespaceAfter"/>
                    <module name="WhitespaceAround"/>

                    <!-- AUTO-FIXABLE: Indentation -->
                    <module name="Indentation">
                        <property name="basicOffset" value="%d"/>
                        <property name="braceAdjustment" value="0"/>
                        <property name="caseIndent" value="%d"/>
                        <property name="throwsIndent" value="%d"/>
                        <property name="lineWrappingIndentation" value="%d"/>
                        <property name="arrayInitIndent" value="%d"/>
                    </module>

                    <!-- AUTO-FIXABLE: Modifier Checks -->
                    <module name="ModifierOrder"/>
                    <module name="RedundantModifier"/>

                    <!-- AUTO-FIXABLE: Checks for blocks -->
                    <module name="AvoidNestedBlocks"/>
                    <module name="EmptyBlock"/>
                    <module name="LeftCurly"/>
                    <module name="NeedBraces"/>
                    <module name="RightCurly"/>

                    <!-- AUTO-FIXABLE: Checks for common coding problems -->
                    <module name="EmptyStatement"/>
                    <module name="EqualsHashCode"/>
                    <module name="HiddenField">
                        <property name="ignoreConstructorParameter" value="true"/>
                        <property name="ignoreSetter" value="true"/>
                    </module>
                    <module name="IllegalInstantiation"/>
                    <module name="InnerAssignment"/>
                    <module name="MissingSwitchDefault"/>
                    <module name="MultipleVariableDeclarations"/>
                    <module name="SimplifyBooleanExpression"/>
                    <module name="SimplifyBooleanReturn"/>

                    <!-- AUTO-FIXABLE: Checks for class design -->
                    <module name="FinalClass"/>
                    <module name="HideUtilityClassConstructor"/>
                    <module name="InterfaceIsType"/>
                    <module name="VisibilityModifier">
                        <property name="packageAllowed" value="false"/>
                        <property name="protectedAllowed" value="true"/>
                        <property name="publicMemberPattern" value="^serialVersionUID$"/>
                    </module>

                    <!-- AUTO-FIXABLE: Miscellaneous other checks -->
                    <module name="ArrayTypeStyle"/>
                    <module name="FinalParameters"/>
                    <module name="TodoComment"/>
                    <module name="UpperEll"/>
                </module>
            </module>
            """
        .formatted(lineLength, indentSize, indentSize, indentSize, indentSize, indentSize);
  }

  /** Run Checkstyle via command line and parse output. */
  private List<FormatterError> runCheckstyleCommandLine(Path sourceFile, boolean autoFix) {
    List<FormatterError> errors = new ArrayList<>();

    try {
      // Find checkstyle JAR or executable
      String checkstyleCommand = findCheckstyleCommand();
      if (checkstyleCommand == null) {
        return errors; // No command available
      }

      // Create temporary config file
      Path configFile = createDefaultCheckstyleConfig();

      // Build command
      List<String> command = new ArrayList<>();
      if (checkstyleCommand.endsWith(".jar")) {
        command.add("java");
        command.add("-jar");
        command.add(checkstyleCommand);
      } else {
        command.add(checkstyleCommand);
      }
      command.add("-c");
      command.add(configFile.toString());

      if (autoFix && supportsAutoFix) {
        command.add("--fix");
      }

      command.add("-f");
      command.add("xml");
      command.add(sourceFile.toString());

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

      // Parse XML output
      if (exitCode == 0 || exitCode == 1) { // 0 = no violations, 1 = violations found
        errors.addAll(parseCheckstyleXmlOutput(output.toString()));
      } else {
        logger.warning("Checkstyle command failed with exit code: " + exitCode);
        logger.warning("Output: " + output.toString());
      }

    } catch (Exception e) {
      logger.log(Level.WARNING, "Error running Checkstyle command", e);
    }

    return errors;
  }

  /** Find Checkstyle command or JAR file. */
  private String findCheckstyleCommand() {
    // Try common locations for Checkstyle
    String[] possibleCommands = {
      "checkstyle",
      "/usr/local/bin/checkstyle",
      System.getProperty("user.home") + "/.local/bin/checkstyle"
    };

    for (String cmd : possibleCommands) {
      try {
        Process process = new ProcessBuilder(cmd, "-version").start();
        int exitCode = process.waitFor();
        if (exitCode == 0) {
          return cmd;
        }
      } catch (Exception e) {
        // Try next command
      }
    }

    // Try to find JAR files
    String[] jarLocations = {
      "/usr/share/java/checkstyle.jar",
      System.getProperty("user.home")
          + "/.m2/repository/com/puppycrawl/tools/checkstyle/*/checkstyle-*.jar"
    };

    for (String jarPattern : jarLocations) {
      try {
        if (jarPattern.contains("*")) {
          // Simple glob expansion for Maven repository
          Path m2Repo =
              Paths.get(
                  System.getProperty("user.home"),
                  ".m2",
                  "repository",
                  "com",
                  "puppycrawl",
                  "tools",
                  "checkstyle");
          if (Files.exists(m2Repo)) {
            try (var stream = Files.walk(m2Repo)) {
              var jarFile =
                  stream
                      .filter(
                          p -> p.toString().endsWith(".jar") && p.toString().contains("checkstyle"))
                      .findFirst();
              if (jarFile.isPresent()) {
                return jarFile.get().toString();
              }
            }
          }
        } else {
          Path jarPath = Paths.get(jarPattern);
          if (Files.exists(jarPath)) {
            return jarPath.toString();
          }
        }
      } catch (Exception e) {
        // Try next location
      }
    }

    return null;
  }

  /** Parse Checkstyle XML output to extract violations. */
  private List<FormatterError> parseCheckstyleXmlOutput(String xmlOutput) {
    List<FormatterError> errors = new ArrayList<>();

    try {
      // Simple XML parsing for Checkstyle output
      String[] lines = xmlOutput.split("\n");
      for (String line : lines) {
        if (line.trim().startsWith("<error ")) {
          FormatterError error = parseCheckstyleErrorLine(line);
          if (error != null) {
            errors.add(error);
          }
        }
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error parsing Checkstyle XML output", e);
    }

    return errors;
  }

  /** Parse a single Checkstyle error line from XML. */
  private FormatterError parseCheckstyleErrorLine(String line) {
    try {
      // Extract attributes using simple regex
      int lineNum = extractIntAttribute(line, "line", 1);
      int column = extractIntAttribute(line, "column", 1);
      String severity = extractStringAttribute(line, "severity", "warning");
      String message = extractStringAttribute(line, "message", "Checkstyle violation");
      String source = extractStringAttribute(line, "source", "");

      // Convert severity
      Severity formatterSeverity = convertSeverity(severity);

      // Create suggestion
      String suggestion = createSuggestion(message, source);

      return new FormatterError(
          formatterSeverity, "Checkstyle: " + message, lineNum, column, suggestion);
    } catch (Exception e) {
      logger.log(Level.FINE, "Error parsing Checkstyle error line: " + line, e);
      return null;
    }
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

  private Severity convertSeverity(String checkstyleSeverity) {
    return switch (checkstyleSeverity.toLowerCase()) {
      case "error" -> Severity.ERROR;
      case "warning" -> Severity.WARNING;
      case "info" -> Severity.INFO;
      default -> Severity.WARNING;
    };
  }

  private String createSuggestion(String message, String source) {
    if (message.contains("Name") && message.contains("must match pattern")) {
      return "Rename to follow Java naming conventions (camelCase for variables/methods, PascalCase for classes)";
    }
    if (message.contains("Line is longer than")) {
      return "Break this line into multiple lines or refactor to reduce length";
    }
    if (message.contains("'{' at column")) {
      return "Move the opening brace to the correct position according to style guidelines";
    }
    if (message.contains("Unused import")) {
      return "Remove this unused import statement";
    }
    if (message.contains("Wrong order for")) {
      return "Reorder imports according to the configured import order";
    }
    if (message.contains("should be final")) {
      return "Add 'final' modifier to this parameter/variable";
    }
    if (message.contains("Missing a Javadoc comment")) {
      return "Add Javadoc documentation for this public method/class";
    }
    if (source.contains("WhitespaceAround")) {
      return "Add proper whitespace around operators and keywords";
    }
    if (source.contains("Indentation")) {
      return "Fix indentation to match the configured style";
    }
    return "Fix this style violation according to Checkstyle rules";
  }
}
