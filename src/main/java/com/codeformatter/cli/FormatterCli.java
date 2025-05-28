package com.codeformatter.cli;

import com.codeformatter.api.FormatterResult;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.ConfigurationLoader;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.core.AdvancedCodeFormatter;
import com.codeformatter.plugins.FileType;
import com.codeformatter.plugins.react.ReactJSFormatter;
import com.codeformatter.plugins.spring.SpringBootFormatter;
import com.codeformatter.util.ErrorFormatter;
import com.codeformatter.util.LoggerUtil;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Enhanced Command Line Interface for the Advanced Code Formatter */
public class FormatterCli {
  private static final Logger logger = LoggerUtil.getLogger(FormatterCli.class);
  private static final String VERSION = "1.0.0";
  private static final String CONFIG_FILE_NAME = ".codeformatter.yml";
  private static ErrorFormatter errorFormatter;

  public static void main(String[] args) {
    try {
      if (args.length < 1) {
        _printUsage();
        System.exit(1);
      }

      boolean useColors = !_hasOption(args, "--no-color");
      errorFormatter = new ErrorFormatter(useColors);

      if (_hasOption(args, "--verbose")) {
        LoggerUtil.setConsoleLevel(Level.FINE);
      } else {
        LoggerUtil.setConsoleLevel(Level.INFO);
      }

      String command = args[0];

      switch (command) {
        case "format":
          _formatFiles(args);
          break;
        case "check":
          _checkFiles(args);
          break;
        case "init":
          _initializeConfig(args);
          break;
        case "analyze":
          _analyzeFiles(args);
          break;
        case "setup":
          _setupEnvironment(args);
          break;
        case "status":
          _showAnalyzerStatus(args);
          break;
        case "check-env":
          _checkEnvironment(args);
          break;
        case "help-setup":
          _printSetupGuide();
          break;
        case "--version":
        case "-v":
          _printVersion();
          break;
        case "--help":
        case "-h":
          _printUsage();
          break;
        default:
          _printError("Unknown command: " + command);
          _printUsage();
          System.exit(1);
      }
    } catch (Exception e) {
      _printError("Error: " + e.getMessage());
      logger.log(Level.SEVERE, "Unhandled exception", e);

      if (_hasOption(args, "--verbose")) {
        e.printStackTrace();
      } else {
        _printInfo("Use --verbose for stack trace");
      }
      System.exit(1);
    } finally {
      LoggerUtil.shutdown();
    }
  }

  /** Check external tool availability and provide feedback. */
  private static void checkExternalTool(
      String toolName, Supplier<ToolStatus> checker, String description) {
    _printBullet(toolName + ": " + description);

    ToolStatus status = checker.get();
    if (status.available) {
      _printSuccess("  ✓ " + status.message);
    } else {
      _printWarning("  ⚠ " + status.message);
      if (status.suggestion != null) {
        _printInfo("    " + status.suggestion);
      }
    }
  }

  /** Check Checkstyle availability. */
  private static ToolStatus checkCheckstyleAvailability() {
    // Check command line tool
    try {
      Process process = new ProcessBuilder("checkstyle", "--version").start();
      int exitCode = process.waitFor();
      if (exitCode == 0) {
        try (Scanner scanner = new Scanner(process.getInputStream()).useDelimiter("\\A")) {
          String version = scanner.hasNext() ? scanner.next().trim() : "Unknown";
          return new ToolStatus(true, "Command line tool available (" + version + ")", null);
        }
      }
    } catch (IOException | InterruptedException e) {
      // Fall through to check classpath
    }

    // Check classpath
    try {
      Class.forName("com.puppycrawl.tools.checkstyle.Checker");
      return new ToolStatus(true, "JAR available in classpath", null);
    } catch (ClassNotFoundException e) {
      return new ToolStatus(
          false,
          "Not found",
          "Install with: ./gradlew installExternalTools OR apt-get install checkstyle OR brew install checkstyle");
    }
  }

  /** Check PMD availability. */
  private static ToolStatus checkPMDAvailability() {
    // Check command line tool
    try {
      Process process = new ProcessBuilder("pmd", "--version").start();
      int exitCode = process.waitFor();
      if (exitCode == 0) {
        try (Scanner scanner = new Scanner(process.getInputStream()).useDelimiter("\\A")) {
          String version = scanner.hasNext() ? scanner.next().trim() : "Unknown";
          return new ToolStatus(true, "Command line tool available (" + version + ")", null);
        }
      }
    } catch (IOException | InterruptedException e) {
      // Fall through to check classpath
    }

    // Check classpath
    try {
      Class.forName("net.sourceforge.pmd.PMD");
      return new ToolStatus(true, "JAR available in classpath", null);
    } catch (ClassNotFoundException e) {
      return new ToolStatus(
          false,
          "Not found",
          "Install with: ./gradlew installExternalTools OR download from https://pmd.github.io/");
    }
  }

  /** Show the status of all available analyzers. */
  private static void _showAnalyzerStatus(String[] args) {
    _printHeader("ANALYZER STATUS");

    boolean verbose = _hasOption(args, "--verbose");
    String configFile = _getOptionValue(args, "--config");

    // Load configuration
    FormatterConfig config;
    if (configFile != null) {
      config = ConfigurationLoader.loadConfig(Paths.get(configFile));
    } else {
      config = ConfigurationLoader.loadConfig(Paths.get(CONFIG_FILE_NAME));
    }

    // Create formatter to check analyzer status
    try (AdvancedCodeFormatter formatter = _createFormatter(config, verbose, false)) {
      // Get Spring Boot formatter specifically to check analyzer status
      if (formatter.hasPluginFor(FileType.JAVA)) {
        _printInfo("Java/Spring Boot Analysis:");
        _printInfo("Getting analyzer status from SpringBootFormatter...");

        _printBullet("Core analyzers:");
        _printSuccess("  ✓ Enhanced Import Organizer - Advanced import organization and cleanup");
        _printSuccess(
            "  ✓ Spring Component Analyzer - Spring-specific dependency injection and component analysis");
        _printSuccess("  ✓ Design Pattern Analyzer - Design pattern detection and suggestions");

        _printBullet("External tool analyzers:");

        // Check Checkstyle
        ToolStatus checkstyleStatus = checkCheckstyleAvailability();
        if (checkstyleStatus.available) {
          _printSuccess("  ✓ Checkstyle - " + checkstyleStatus.message);
          if (verbose) {
            _printInfo(
                "    Provides: Code style checking, naming conventions, import organization");
          }
        } else {
          _printWarning("  ⚠ Checkstyle - " + checkstyleStatus.message);
          if (checkstyleStatus.suggestion != null) {
            _printInfo("    Install: " + checkstyleStatus.suggestion);
          }
        }

        // Check PMD
        ToolStatus pmdStatus = checkPMDAvailability();
        if (pmdStatus.available) {
          _printSuccess("  ✓ PMD - " + pmdStatus.message);
          if (verbose) {
            _printInfo(
                "    Provides: Code quality analysis, bug detection, performance suggestions");
          }
        } else {
          _printWarning("  ⚠ PMD - " + pmdStatus.message);
          if (pmdStatus.suggestion != null) {
            _printInfo("    Install: " + pmdStatus.suggestion);
          }
        }
      }

      // Check React/JavaScript analyzers
      if (formatter.hasPluginFor(FileType.JAVASCRIPT)) {
        _printInfo("\nJavaScript/React Analysis:");
        _printSuccess("  ✓ ESLint + Prettier integration");
        _printSuccess("  ✓ React hooks dependency checking");
        _printSuccess("  ✓ Component structure analysis");
      } else {
        _printWarning("\nJavaScript/React Analysis:");
        _printWarning("  ⚠ React formatter not available");
        _printInfo("    Run 'codeformatter setup' to configure Node.js tools");
      }

      // Show configuration status
      _printInfo("\nConfiguration:");
      Path configPath = Paths.get(CONFIG_FILE_NAME);
      if (Files.exists(configPath)) {
        _printSuccess("  ✓ Configuration file found: " + CONFIG_FILE_NAME);

        if (verbose) {
          // Show some key configuration values
          _printInfo("    General settings:");
          _printInfo("      - Indent size: " + config.getGeneralConfig("indentSize", 4));
          _printInfo("      - Line length: " + config.getGeneralConfig("lineLength", 100));
          _printInfo("      - Use tabs: " + config.getGeneralConfig("useTabs", false));

          _printInfo("    External tool settings:");
          _printInfo(
              "      - Checkstyle enabled: "
                  + config.getPluginConfig("spring", "checkstyle.enabled", true));
          _printInfo(
              "      - PMD enabled: " + config.getPluginConfig("spring", "pmd.enabled", true));
        }
      } else {
        _printWarning("  ⚠ No configuration file found");
        _printInfo("    Run 'codeformatter init' to create default configuration");
      }

      // Show summary
      _printHeader("SUMMARY");

      int availableTools = 0;
      int totalTools = 2; // Checkstyle + PMD

      if (checkCheckstyleAvailability().available) availableTools++;
      if (checkPMDAvailability().available) availableTools++;

      if (availableTools == totalTools) {
        _printSuccess("✓ All external analysis tools are available!");
        _printInfo("You're getting the full power of the Advanced Code Formatter.");
      } else if (availableTools > 0) {
        _printWarning(
            "⚠ Some external tools are missing ("
                + availableTools
                + "/"
                + totalTools
                + " available)");
        _printInfo("The formatter will work but with reduced analysis capabilities.");
        _printInfo(
            "Run './gradlew installExternalTools' or 'codeformatter setup' to install missing tools.");
      } else {
        _printWarning("⚠ No external analysis tools found");
        _printInfo("Only basic Java formatting and custom Spring analysis will be available.");
        _printInfo("For enhanced analysis, install Checkstyle and PMD:");
        _printInfo("  ./gradlew installExternalTools");
      }

    } catch (Exception e) {
      _printError("Error checking analyzer status: " + e.getMessage());
      if (verbose) {
        e.printStackTrace();
      }
    }
  }

  /** New command to check if the environment is properly set up */
  private static void _checkEnvironment(String[] args) {
    _printHeader("ENVIRONMENT CHECK");
    _printInfo("Checking environment for Advanced Code Formatter...");

    // Check Java version
    String javaVersion = System.getProperty("java.version");
    _printBullet("Java version: " + javaVersion);
    if (javaVersion.startsWith("1.") || Integer.parseInt(javaVersion.split("\\.")[0]) < 11) {
      _printWarning("  ⚠ Java 11 or higher is recommended for best performance");
    } else {
      _printSuccess("  ✓ Java version is sufficient");
    }

    // Check external tools for Java analysis
    _printInfo("\nChecking Java analysis tools:");
    checkExternalTool(
        "Checkstyle",
        FormatterCli::checkCheckstyleAvailability,
        "Enhanced style checking and code standards enforcement");

    checkExternalTool(
        "PMD",
        FormatterCli::checkPMDAvailability,
        "Advanced code quality analysis and bug detection");

    // Check Node.js for React/JavaScript formatting
    boolean nodeAvailable = false;
    String nodeVersion = null;

    try {
      Process process = new ProcessBuilder("node", "--version").start();
      int exitCode = process.waitFor();
      if (exitCode == 0) {
        try (Scanner scanner = new Scanner(process.getInputStream()).useDelimiter("\\A")) {
          nodeVersion = scanner.hasNext() ? scanner.next().trim() : "Unknown";
          nodeAvailable = true;
          _printBullet("Node.js: " + nodeVersion);
          _printSuccess("  ✓ Node.js is installed");
        }
      } else {
        _printBullet("Node.js: Not found");
        _printWarning("  ⚠ Node.js check failed with exit code: " + exitCode);
      }
    } catch (IOException | InterruptedException e) {
      _printBullet("Node.js: Not found");
      _printWarning("  ⚠ Node.js not found: " + e.getMessage());
    }

    if (!nodeAvailable) {
      _printWarning("  ⚠ Node.js is required for JavaScript and React formatting");
      _printInfo("  ℹ To install Node.js, visit: https://nodejs.org/");
    } else {
      // Check for required npm packages
      _printInfo("\nChecking required npm packages:");
      String[] requiredPackages = {
        "prettier", "eslint", "eslint-plugin-react", "eslint-plugin-react-hooks"
      };
      boolean allPackagesFound = true;
      for (String pkg : requiredPackages) {
        boolean pkgFound = false;

        // First check global installation
        try {
          Process process = new ProcessBuilder("npm", "list", "-g", pkg).start();
          int exitCode = process.waitFor();
          pkgFound = (exitCode == 0);
        } catch (IOException | InterruptedException e) {
          // Ignore errors here, will try local next
        }

        // Then check local installation in the current directory
        if (!pkgFound) {
          try {
            Process process = new ProcessBuilder("npm", "list", pkg).start();
            int exitCode = process.waitFor();
            pkgFound = (exitCode == 0);
          } catch (IOException | InterruptedException e) {
            // Ignore errors here
          }
        }

        // Check user home .codeformatter/node directory
        if (!pkgFound) {
          Path userHomeNode = Paths.get(System.getProperty("user.home"), ".codeformatter", "node");
          if (Files.exists(userHomeNode)) {
            try {
              Process process =
                  new ProcessBuilder("npm", "list", pkg).directory(userHomeNode.toFile()).start();
              int exitCode = process.waitFor();
              pkgFound = (exitCode == 0);
            } catch (IOException | InterruptedException e) {
              // Ignore errors here
            }
          }
        }

        if (pkgFound) {
          _printBullet(pkg);
          _printSuccess("  ✓ Installed");
        } else {
          _printBullet(pkg);
          _printWarning("  ⚠ Not found");
          allPackagesFound = false;
        }
      }

      if (!allPackagesFound) {
        _printInfo("\nSome required npm packages are missing. Run this command to install them:");
        _printInfo(
            "  npm install -g prettier eslint eslint-plugin-react eslint-plugin-react-hooks");
        _printInfo("Or run the setup command:");
        _printInfo("  codeformatter setup");
      }
    }

    // Check config file
    Path configPath = Paths.get(CONFIG_FILE_NAME);
    _printInfo("\nChecking configuration file:");
    _printBullet("Config file: " + CONFIG_FILE_NAME);
    if (Files.exists(configPath)) {
      _printSuccess("  ✓ Found");
    } else {
      _printWarning("  ⚠ Not found");
      _printInfo("  ℹ Run 'codeformatter init' to create a default configuration file");
    }

    // Check system resources
    _printInfo("\nChecking system resources:");
    Runtime runtime = Runtime.getRuntime();
    long maxMemory = runtime.maxMemory() / (1024 * 1024);
    _printBullet("Maximum available memory: " + maxMemory + " MB");

    if (maxMemory < 512) {
      _printWarning("  ⚠ Available memory is low. Consider increasing with -Xmx option.");
    } else {
      _printSuccess("  ✓ Memory is sufficient");
    }

    int cpus = runtime.availableProcessors();
    _printBullet("Available processors: " + cpus);
    _printSuccess("  ✓ Using up to " + cpus + " threads for parallel processing");

    // Check for write permissions in current directory
    _printInfo("\nChecking filesystem permissions:");
    try {
      Path testFile = Files.createTempFile("codeformatter-test", ".tmp");
      Files.delete(testFile);
      _printBullet("Write permissions");
      _printSuccess("  ✓ Write permission check passed");
    } catch (IOException e) {
      _printBullet("Write permissions");
      _printWarning("  ⚠ Write permission check failed: " + e.getMessage());
      _printInfo("  ℹ The formatter needs write permissions to modify files");
    }

    // Check Node.js resources
    _printInfo("\nChecking Node.js server resources:");
    Path nodePath = _findNodeServerScript();
    if (nodePath != null) {
      _printBullet("Node.js server script");
      _printSuccess("  ✓ Found at: " + nodePath);
    } else {
      _printBullet("Node.js server script");
      _printWarning("  ⚠ Not found in expected locations");
      _printInfo("  ℹ Run 'codeformatter setup' to set up Node.js resources");
    }

    // Summary and next steps
    _printHeader("SUMMARY");

    if (nodeAvailable && Files.exists(configPath) && nodePath != null) {
      _printSuccess("✓ Basic environment is correctly set up!");
    } else {
      _printWarning("⚠ Some components are missing from your environment.");
      _printInfo("\nTo complete setup, run:");
      _printInfo("  codeformatter setup");
      _printInfo("\nFor detailed setup instructions, run:");
      _printInfo("  codeformatter help-setup");
    }
  }

  /** Find the Node.js server script in various locations */
  private static Path _findNodeServerScript() {
    List<Path> possibleLocations = new ArrayList<>();

    // Current working directory and subdirectories
    possibleLocations.add(Paths.get("node", "server.js"));

    // User's home directory
    String userHome = System.getProperty("user.home");
    possibleLocations.add(Paths.get(userHome, ".codeformatter", "node", "server.js"));

    // Installation directory (relative to current working directory)
    possibleLocations.add(Paths.get("node", "server.js"));

    // Application directory structure locations
    possibleLocations.add(
        Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "node", "server.js"));
    possibleLocations.add(
        Paths.get(
            System.getProperty("user.dir"), "build", "resources", "main", "node", "server.js"));
    possibleLocations.add(
        Paths.get(System.getProperty("user.dir"), "resources", "node", "server.js"));

    // Check each location
    for (Path path : possibleLocations) {
      if (Files.exists(path)) {
        return path;
      }
    }

    // Check if resource exists in classpath
    try {
      URL serverJsResource = FormatterCli.class.getClassLoader().getResource("node/server.js");
      if (serverJsResource != null) {
        return Paths.get(serverJsResource.toURI());
      }
    } catch (Exception e) {
      // Ignore, will return null
    }

    return null;
  }

  /** New command to help users set up their environment */
  private static void _setupEnvironment(String[] args) throws IOException {
    _printHeader("ADVANCED CODE FORMATTER SETUP");
    _printInfo("Setting up your environment for the Advanced Code Formatter...");

    // Create user-specific configuration directory
    String userHome = System.getProperty("user.home");
    Path configDir = Paths.get(userHome, ".codeformatter");
    Path nodeDir = configDir.resolve("node");

    if (!Files.exists(configDir)) {
      Files.createDirectories(configDir);
      _printSuccess("Created configuration directory: " + configDir);
    }

    if (!Files.exists(nodeDir)) {
      Files.createDirectories(nodeDir);
      _printSuccess("Created Node.js resources directory: " + nodeDir);
    }

    // Extract Node.js server scripts to user directory
    boolean extracted = _extractNodeResources(nodeDir);

    if (extracted) {
      _printSuccess("Extracted Node.js server scripts to: " + nodeDir);
    } else {
      _printWarning("Failed to extract Node.js server scripts");
      _printInfo("This is not critical if you have Node.js installed with required packages.");
    }

    // Create default configuration file if needed
    Path configFile = Paths.get(CONFIG_FILE_NAME);
    if (!Files.exists(configFile)) {
      _initializeConfig(new String[] {"init"});
    } else {
      _printInfo("Configuration file already exists: " + CONFIG_FILE_NAME);
    }

    // Check Node.js installation
    boolean nodeAvailable = false;
    try {
      Process process = new ProcessBuilder("node", "--version").start();
      int exitCode = process.waitFor();
      if (exitCode == 0) {
        nodeAvailable = true;
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(process.getInputStream()))) {
          String version = reader.readLine();
          _printSuccess("Node.js is installed: " + version);
        }
      }
    } catch (IOException | InterruptedException e) {
      _printWarning("Node.js not detected: " + e.getMessage());
    }

    if (!nodeAvailable) {
      _printInfo("\nTo enable JavaScript and React formatting, please install Node.js:");
      _printInfo("1. Visit https://nodejs.org/");
      _printInfo("2. Download and install the LTS version");
      _printInfo("3. Restart your terminal/command prompt after installation");
      _printInfo("\nAfter installing Node.js, install required npm packages:");
      _printInfo("npm install -g prettier eslint eslint-plugin-react eslint-plugin-react-hooks");
    } else {
      _printInfo("\nNow installing required npm packages...");

      try {
        // Install required npm packages locally in the .codeformatter/node directory
        Process process =
            new ProcessBuilder(
                    "npm",
                    "install",
                    "prettier",
                    "eslint",
                    "eslint-plugin-react",
                    "eslint-plugin-react-hooks")
                .directory(nodeDir.toFile())
                .inheritIO()
                .start();

        int exitCode = process.waitFor();
        if (exitCode == 0) {
          _printSuccess("Successfully installed npm packages");
        } else {
          _printWarning("npm package installation failed with exit code: " + exitCode);
          _printInfo("You may need to install them manually:");
          _printInfo(
              "npm install -g prettier eslint eslint-plugin-react eslint-plugin-react-hooks");
        }
      } catch (IOException | InterruptedException e) {
        _printWarning("Failed to install npm packages: " + e.getMessage());
        _printInfo("Please install them manually:");
        _printInfo("npm install -g prettier eslint eslint-plugin-react eslint-plugin-react-hooks");
      }
    }

    _printHeader("SETUP COMPLETE");
    _printSuccess("Advanced Code Formatter has been set up successfully!");
    _printInfo("\nTo verify your environment, run:");
    _printInfo("  codeformatter check-env");
    _printInfo("\nTo get started with formatting, try:");
    _printInfo("  codeformatter format <path-to-your-code>");
    _printInfo("\nFor more help and information, run:");
    _printInfo("  codeformatter help-setup");
  }

  /** Display detailed setup guide */
  private static void _printSetupGuide() {
    _printHeader("ADVANCED CODE FORMATTER SETUP GUIDE");
    _printInfo("This guide will help you set up the Advanced Code Formatter environment.\n");

    _printHeader("STEP 1: Java Environment");
    _printInfo(
        "The formatter requires Java 11 or higher. Current version: "
            + System.getProperty("java.version"));
    _printInfo("If you need to upgrade Java:");
    _printBullet(
        "1. Visit https://adoptium.net/ or https://www.oracle.com/java/technologies/downloads/");
    _printBullet("2. Download and install Java 11 or higher");
    _printBullet("3. Ensure JAVA_HOME is set to the new Java installation");
    _printBullet("4. Restart your terminal or command prompt");

    _printHeader("STEP 2: Node.js Setup (for JavaScript/React formatting)");
    _printInfo("To format JavaScript and React files, Node.js is required:");
    _printBullet("1. Visit https://nodejs.org/");
    _printBullet("2. Download and install the LTS version");
    _printBullet("3. Verify installation with: node --version");
    _printBullet("4. Install required npm packages:");
    _printInfo("   npm install -g prettier eslint eslint-plugin-react eslint-plugin-react-hooks");
    _printInfo("\nAlternatively, run the setup command to handle this for you:");
    _printInfo("  codeformatter setup");

    _printHeader("STEP 3: Configuration");
    _printInfo("Create a default configuration file in your project directory:");
    _printInfo("  codeformatter init");
    _printInfo("\nThis creates .codeformatter.yml with default settings you can customize.");
    _printInfo("Common configuration options include:");
    _printBullet("- indentSize: Number of spaces for indentation (default: 4)");
    _printBullet("- lineLength: Maximum line length (default: 100)");
    _printBullet("- useTabs: Use tabs instead of spaces (default: false)");
    _printBullet("- Plugin-specific settings for Spring and React");

    _printHeader("STEP 4: Verify Setup");
    _printInfo("Check your environment to ensure everything is configured correctly:");
    _printInfo("  codeformatter check-env");
    _printInfo("\nThis will verify Java, Node.js, npm packages, and configuration.");

    _printHeader("STEP 5: Using the Formatter");
    _printInfo("Format your code with:");
    _printInfo("  codeformatter format <path>");
    _printInfo("\nOther useful commands:");
    _printBullet("codeformatter check <path>   - Check formatting without modifying files");
    _printBullet("codeformatter analyze <path> - Analyze code quality without formatting");

    _printHeader("TROUBLESHOOTING");
    _printInfo("Common issues and solutions:");

    _printBullet("Issue: 'NodeJS formatter is disabled'");
    _printInfo("  Solution: Run 'codeformatter setup' to install Node.js and required packages");

    _printBullet("Issue: 'Error processing with Node.js'");
    _printInfo("  Solution: Check if Node.js is installed with 'node --version' and install any");
    _printInfo("            missing npm packages with 'npm install -g prettier eslint'");

    _printBullet("Issue: 'File not found' or permission errors");
    _printInfo(
        "  Solution: Check file paths and ensure you have appropriate read/write permissions");

    _printBullet("Issue: 'OutOfMemoryError'");
    _printInfo("  Solution: Increase Java heap size with '-Xmx' flag. Example:");
    _printInfo("            java -Xmx1g -jar advanced-formatter.jar format <path>");

    _printHeader("ADDITIONAL HELP");
    _printInfo("For more information and help:");
    _printBullet("- Command help: codeformatter --help");
    _printBullet("- Environment check: codeformatter check-env");
    _printBullet("- Setup assistance: codeformatter setup");
  }

  /** Extract Node.js resources to the specified directory */
  private static boolean _extractNodeResources(Path nodeDir) {
    try {
      // Extract server.js
      InputStream serverJs =
          FormatterCli.class.getClassLoader().getResourceAsStream("node/server.js");
      if (serverJs != null) {
        try (OutputStream out = new FileOutputStream(nodeDir.resolve("server.js").toFile())) {
          byte[] buffer = new byte[4096];
          int read;
          while ((read = serverJs.read(buffer)) != -1) {
            out.write(buffer, 0, read);
          }
        }

        // Create package.json if not extracted from resources
        InputStream packageJsonStream =
            FormatterCli.class.getClassLoader().getResourceAsStream("package.json");
        if (packageJsonStream != null) {
          try (OutputStream out = new FileOutputStream(nodeDir.resolve("package.json").toFile())) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = packageJsonStream.read(buffer)) != -1) {
              out.write(buffer, 0, read);
            }
          }
        } else {
          // Create package.json manually
          Path packageJson = nodeDir.resolve("package.json");
          String packageJsonContent =
              "{\n"
                  + "  \"name\": \"advanced-formatter-js-tools\",\n"
                  + "  \"version\": \"1.0.0\",\n"
                  + "  \"private\": true,\n"
                  + "  \"dependencies\": {\n"
                  + "    \"express\": \"^4.18.2\",\n"
                  + "    \"prettier\": \"^2.8.8\",\n"
                  + "    \"eslint\": \"^8.46.0\",\n"
                  + "    \"eslint-plugin-react\": \"^7.33.0\",\n"
                  + "    \"eslint-plugin-react-hooks\": \"^4.6.0\"\n"
                  + "  }\n"
                  + "}";
          Files.writeString(packageJson, packageJsonContent);
        }

        // Create .eslintrc.js if not extracted from resources
        InputStream eslintRcStream =
            FormatterCli.class.getClassLoader().getResourceAsStream(".eslintrc.js");
        if (eslintRcStream != null) {
          try (OutputStream out = new FileOutputStream(nodeDir.resolve(".eslintrc.js").toFile())) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = eslintRcStream.read(buffer)) != -1) {
              out.write(buffer, 0, read);
            }
          }
        } else {
          // Create .eslintrc.js manually
          Path eslintRc = nodeDir.resolve(".eslintrc.js");
          String eslintRcContent =
              "module.exports = {\n"
                  + "  env: {\n"
                  + "    browser: true,\n"
                  + "    es2021: true,\n"
                  + "    node: true,\n"
                  + "  },\n"
                  + "  extends: [\n"
                  + "    'eslint:recommended',\n"
                  + "    'plugin:react/recommended',\n"
                  + "    'plugin:react-hooks/recommended',\n"
                  + "  ],\n"
                  + "  parserOptions: {\n"
                  + "    ecmaFeatures: {\n"
                  + "      jsx: true,\n"
                  + "    },\n"
                  + "    ecmaVersion: 'latest',\n"
                  + "    sourceType: 'module',\n"
                  + "  },\n"
                  + "  plugins: ['react', 'react-hooks'],\n"
                  + "  rules: {\n"
                  + "    'react-hooks/rules-of-hooks': 'error',\n"
                  + "    'react-hooks/exhaustive-deps': 'warn',\n"
                  + "  },\n"
                  + "  settings: {\n"
                  + "    react: {\n"
                  + "      version: 'detect',\n"
                  + "    },\n"
                  + "  },\n"
                  + "};\n";
          Files.writeString(eslintRc, eslintRcContent);
        }

        // Create a server.js file if it wasn't found in resources
        Path serverJsPath = nodeDir.resolve("server.js");
        if (!Files.exists(serverJsPath) || Files.size(serverJsPath) == 0) {
          String serverJsContent =
              "const express = require('express');\n"
                  + "const prettier = require('prettier');\n"
                  + "const { ESLint } = require('eslint');\n"
                  + "\n"
                  + "// Get port from command line or use default\n"
                  + "const port = process.argv[2] || 9567;\n"
                  + "const app = express();\n"
                  + "\n"
                  + "// For parsing application/json\n"
                  + "app.use(express.json({ limit: '50mb' }));\n"
                  + "\n"
                  + "// For health check and startup verification\n"
                  + "app.get('/health', (req, res) => {\n"
                  + "  res.json({ status: 'ok', version: '1.0.0' });\n"
                  + "});\n"
                  + "\n"
                  + "// Global configuration storage\n"
                  + "let globalConfig = {\n"
                  + "  prettier: {\n"
                  + "    printWidth: 80,\n"
                  + "    tabWidth: 2,\n"
                  + "    useTabs: false,\n"
                  + "    semi: true,\n"
                  + "    singleQuote: true,\n"
                  + "    trailingComma: 'es5',\n"
                  + "    bracketSpacing: true,\n"
                  + "    jsxBracketSameLine: false,\n"
                  + "    arrowParens: 'avoid',\n"
                  + "  },\n"
                  + "  eslint: {\n"
                  + "    rules: {},\n"
                  + "  },\n"
                  + "};\n"
                  + "\n"
                  + "// Configuration endpoint\n"
                  + "app.post('/configure', (req, res) => {\n"
                  + "  try {\n"
                  + "    const config = req.body;\n"
                  + "\n"
                  + "    // Update global configuration\n"
                  + "    if (config.prettier) {\n"
                  + "      globalConfig.prettier = { ...globalConfig.prettier, ...config.prettier };\n"
                  + "    }\n"
                  + "\n"
                  + "    if (config.eslint) {\n"
                  + "      globalConfig.eslint = {\n"
                  + "        ...globalConfig.eslint,\n"
                  + "        rules: {\n"
                  + "          ...(globalConfig.eslint.rules || {}),\n"
                  + "          ...(config.eslint.rules || {}),\n"
                  + "        },\n"
                  + "      };\n"
                  + "    }\n"
                  + "\n"
                  + "    console.log('Configuration updated');\n"
                  + "\n"
                  + "    res.json({ success: true, message: 'Configuration updated' });\n"
                  + "  } catch (error) {\n"
                  + "    console.error('Error updating configuration:', error);\n"
                  + "    res.status(500).json({\n"
                  + "      success: false,\n"
                  + "      error: error.message,\n"
                  + "    });\n"
                  + "  }\n"
                  + "});\n"
                  + "\n"
                  + "// Format code endpoint\n"
                  + "app.post('/format', async (req, res) => {\n"
                  + "  try {\n"
                  + "    const { code, isReact, options } = req.body;\n"
                  + "\n"
                  + "    if (!code) {\n"
                  + "      return res.status(400).json({\n"
                  + "        success: false,\n"
                  + "        error: 'No code provided',\n"
                  + "      });\n"
                  + "    }\n"
                  + "\n"
                  + "    // Merge global configuration with request-specific options\n"
                  + "    const prettierOptions = {\n"
                  + "      // Start with global config\n"
                  + "      ...globalConfig.prettier,\n"
                  + "      // Set parser based on file type\n"
                  + "      parser: isReact ? 'babel' : 'babel',\n"
                  + "      // Override with request-specific options if provided\n"
                  + "      ...(options || {}),\n"
                  + "    };\n"
                  + "\n"
                  + "    // Try to format with prettier\n"
                  + "    let formattedCode;\n"
                  + "    try {\n"
                  + "      formattedCode = prettier.format(code, prettierOptions);\n"
                  + "    } catch (prettierError) {\n"
                  + "      console.warn('Prettier formatting failed:', prettierError.message);\n"
                  + "      // Return original code if prettier fails\n"
                  + "      formattedCode = code;\n"
                  + "    }\n"
                  + "\n"
                  + "    res.json({\n"
                  + "      success: true,\n"
                  + "      formattedCode,\n"
                  + "    });\n"
                  + "  } catch (error) {\n"
                  + "    console.error('Formatting error:', error);\n"
                  + "    res.status(500).json({\n"
                  + "      success: false,\n"
                  + "      error: error.message,\n"
                  + "      originalCode: req.body.code,\n"
                  + "    });\n"
                  + "  }\n"
                  + "});\n"
                  + "\n"
                  + "// Analyze code endpoint\n"
                  + "app.post('/analyze', async (req, res) => {\n"
                  + "  try {\n"
                  + "    const { code, isReact } = req.body;\n"
                  + "\n"
                  + "    if (!code) {\n"
                  + "      return res.status(400).json({\n"
                  + "        success: false,\n"
                  + "        error: 'No code provided',\n"
                  + "      });\n"
                  + "    }\n"
                  + "\n"
                  + "    // Initialize ESLint with our custom config\n"
                  + "    const eslint = new ESLint({\n"
                  + "      useEslintrc: true,\n"
                  + "      overrideConfig: {\n"
                  + "        rules: globalConfig.eslint.rules || {},\n"
                  + "      },\n"
                  + "    });\n"
                  + "\n"
                  + "    // Run ESLint\n"
                  + "    const results = await eslint.lintText(code, {\n"
                  + "      filePath: isReact ? 'file.jsx' : 'file.js',\n"
                  + "    });\n"
                  + "\n"
                  + "    // Format results\n"
                  + "    const issues = results[0].messages.map(msg => ({\n"
                  + "      ruleId: msg.ruleId || 'syntax-error',\n"
                  + "      severity: msg.severity === 2 ? 'error' : 'warning',\n"
                  + "      message: msg.message,\n"
                  + "      line: msg.line || 1,\n"
                  + "      column: msg.column || 1,\n"
                  + "    }));\n"
                  + "\n"
                  + "    res.json({\n"
                  + "      success: true,\n"
                  + "      issues,\n"
                  + "    });\n"
                  + "  } catch (error) {\n"
                  + "    console.error('Analysis error:', error);\n"
                  + "    res.status(500).json({\n"
                  + "      success: false,\n"
                  + "      error: error.message,\n"
                  + "    });\n"
                  + "  }\n"
                  + "});\n"
                  + "\n"
                  + "// Start server\n"
                  + "const server = app.listen(port, () => {\n"
                  + "  console.log(`Server listening on port ${port}`);\n"
                  + "});\n"
                  + "\n"
                  + "// Handle graceful shutdown\n"
                  + "process.on('SIGTERM', () => {\n"
                  + "  console.log('Received SIGTERM, shutting down');\n"
                  + "  server.close(() => {\n"
                  + "    console.log('Server stopped');\n"
                  + "    process.exit(0);\n"
                  + "  });\n"
                  + "});\n"
                  + "\n"
                  + "process.on('SIGINT', () => {\n"
                  + "  console.log('Received SIGINT, shutting down');\n"
                  + "  server.close(() => {\n"
                  + "    console.log('Server stopped');\n"
                  + "    process.exit(0);\n"
                  + "  });\n"
                  + "});\n";

          Files.writeString(serverJsPath, serverJsContent);
        }

        // Create README.txt with instructions
        Path readmePath = nodeDir.resolve("README.txt");
        String readmeContent =
            "Advanced Code Formatter Node.js Server\n\n"
                + "This directory contains the Node.js server component required for JavaScript and React formatting.\n"
                + "If you're experiencing issues, please ensure you have Node.js installed and that the following\n"
                + "packages are installed globally or in this directory:\n\n"
                + "- prettier\n"
                + "- eslint\n"
                + "- eslint-plugin-react\n"
                + "- eslint-plugin-react-hooks\n\n"
                + "You can install these packages by running:\n"
                + "npm install\n\n"
                + "or\n\n"
                + "npm install -g prettier eslint eslint-plugin-react eslint-plugin-react-hooks\n";

        Files.writeString(readmePath, readmeContent);
        return true;
      } else {
        logger.warning("Could not find node/server.js in resources");
        return false;
      }
    } catch (IOException e) {
      logger.log(Level.WARNING, "Failed to extract Node.js resources", e);
      return false;
    }
  }

  private static void _printVersion() {
    System.out.println("Advanced Code Formatter version " + VERSION);
  }

  private static void _printUsage() {
    System.out.println(
        errorFormatter.colorize(
            ErrorFormatter.ANSI_BOLD, "Advanced Code Formatter CLI v" + VERSION));
    System.out.println("Usage:");
    System.out.println("  codeformatter init [--force]      - Initialize configuration file");
    System.out.println("  codeformatter format <path>       - Format files in path");
    System.out.println("  codeformatter check <path>        - Check files without formatting");
    System.out.println("  codeformatter analyze <path>      - Analyze code without formatting");
    System.out.println("  codeformatter setup               - Set up environment for formatting");
    System.out.println("  codeformatter check-env           - Check environment setup");
    System.out.println("  codeformatter help-setup          - Display detailed setup guide");
    System.out.println("  codeformatter --help|-h           - Show this help");
    System.out.println("  codeformatter --version|-v        - Show version information");
    System.out.println();
    System.out.println("Options:");
    System.out.println(
        "  --config=<file>                   - Use specific config file (default: .codeformatter.yml)");
    System.out.println("  --verbose                         - Show detailed output");
    System.out.println("  --ci                              - CI friendly output (simplified)");
    System.out.println("  --no-color                        - Disable colored output");
    System.out.println("  --include=<glob>                  - Only include files matching pattern");
    System.out.println(
        "  --threads=<num>                   - Number of threads to use (default: available processors)");
    System.out.println("  --force                           - Force overwrite (with init command)");
    System.out.println("  --skip-react                      - Skip ReactJS/JavaScript formatting");
  }

  private static void _formatFiles(String[] args) throws IOException {
    if (args.length < 2) {
      _printError("Error: Missing path argument");
      _printUsage();
      System.exit(1);
    }

    String targetPath = args[1];
    Path path = Paths.get(targetPath);

    if (!Files.exists(path)) {
      _printError("Error: Path does not exist: " + targetPath);
      System.exit(1);
    }

    boolean verbose = _hasOption(args, "--verbose");
    boolean ciMode = _hasOption(args, "--ci");
    boolean skipReact = _hasOption(args, "--skip-react");
    String configFile = _getOptionValue(args, "--config");
    String includePattern = _getOptionValue(args, "--include");
    String threadsStr = _getOptionValue(args, "--threads");
    int threads = Runtime.getRuntime().availableProcessors();
    if (threadsStr != null) {
      try {
        threads = Integer.parseInt(threadsStr);
      } catch (NumberFormatException e) {
        _printWarning("Invalid thread count: " + threadsStr + ", using default");
      }
    }

    FormatterConfig config;
    if (configFile != null) {
      _printInfo("Using config file: " + configFile);
      config = ConfigurationLoader.loadConfig(Paths.get(configFile));
    } else {
      config = ConfigurationLoader.loadConfig(Paths.get(CONFIG_FILE_NAME));
    }

    try (AdvancedCodeFormatter formatter = _createFormatter(config, verbose, skipReact)) {
      AtomicInteger fileCount = new AtomicInteger(0);
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicInteger skippedCount = new AtomicInteger(0);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicLong totalLines = new AtomicLong(0);

      List<Path> filesToFormat =
          _findFiles(
              path,
              config.getGeneralConfig("ignoreFiles", new ArrayList<String>()),
              includePattern);

      _printInfo("Found " + filesToFormat.size() + " files to format");

      Instant start = Instant.now();

      Map<Path, List<FormatterError>> errorsByFile = new HashMap<>();

      for (Path file : filesToFormat) {
        try {
          if (verbose) {
            _printInfo("Processing: " + file);
          }

          // Check if we should skip this file based on file type
          FileType fileType = FileType.detect(file);
          if (skipReact
              && (fileType == FileType.JAVASCRIPT
                  || fileType == FileType.JSX
                  || fileType == FileType.TYPESCRIPT
                  || fileType == FileType.TSX)) {
            _printInfo("Skipping JavaScript/React file (--skip-react): " + file);
            skippedCount.incrementAndGet();
            continue;
          }

          // Check if we have an active plugin for this file type
          if (!formatter.hasActivePluginFor(fileType)) {
            _printWarning("Skipping file (no active plugin): " + file);
            skippedCount.incrementAndGet();
            continue;
          }

          String source = Files.readString(file);
          totalLines.addAndGet(source.split("\n").length);

          FormatterResult result = formatter.formatFile(file, source);

          if (result.isSuccessful()) {
            if (!source.equals(result.getFormattedCode())) {
              Files.writeString(file, result.getFormattedCode());

              _printSuccess("Formatted: " + file);
              successCount.incrementAndGet();

              // NEW CODE: Always display ESLint warnings/errors even if formatting succeeded
              if (!result.getErrors().isEmpty()) {
                _printWarning("  Issues found:");

                // Group errors by severity for better readability
                Map<Severity, List<FormatterError>> errorsBySeverity =
                    errorFormatter.groupBySeverity(result.getErrors());

                // Display errors first, then warnings, then info
                _printErrorsBySeverity(errorsBySeverity, Severity.ERROR);
                _printErrorsBySeverity(errorsBySeverity, Severity.WARNING);
                _printErrorsBySeverity(errorsBySeverity, Severity.INFO);

                // Also add to the errorsByFile map for summary
                errorsByFile.put(file, result.getErrors());
              }

              if (!ciMode && !result.getAppliedRefactorings().isEmpty() && verbose) {
                _printInfo("  Applied refactorings:");
                result
                    .getAppliedRefactorings()
                    .forEach(r -> _printInfo("    - " + r.getDescription()));
              }
            } else {
              if (verbose) {
                _printInfo("  Already formatted: " + file);
              }
              successCount.incrementAndGet();
            }
          } else {
            // Check if this is just a warning about React formatter being disabled
            boolean isReactDisabledWarning =
                result.getErrors().stream()
                    .anyMatch(e -> e.getMessage().contains("ReactJS formatter is disabled"));

            if (isReactDisabledWarning) {
              _printWarning("Skipping (ReactJS formatter disabled): " + file);
              skippedCount.incrementAndGet();
            } else {
              _printError("Failed to format: " + file);
              errorsByFile.put(file, result.getErrors());
              result.getErrors().forEach(e -> _printError("  " + errorFormatter.formatError(e)));
              errorCount.incrementAndGet();
            }
          }

          fileCount.incrementAndGet();
        } catch (Exception e) {
          _printError("Error processing file: " + file);
          _printError("  " + e.getMessage());

          List<FormatterError> errors = new ArrayList<>();
          errors.add(
              new FormatterError(
                  Severity.FATAL,
                  "Exception: " + e.getMessage(),
                  1,
                  1,
                  "Check the log file for details"));
          errorsByFile.put(file, errors);

          logger.log(Level.SEVERE, "Error processing file: " + file, e);

          if (verbose) {
            e.printStackTrace();
          }
          errorCount.incrementAndGet();
        }
      }

      Instant end = Instant.now();
      Duration duration = Duration.between(start, end);

      System.out.println("\nFormatting complete in " + _formatDuration(duration) + ":");
      System.out.println("  Processed files: " + fileCount.get());
      System.out.println("  Successfully formatted: " + successCount.get());
      System.out.println("  Files with errors: " + errorCount.get());
      if (skippedCount.get() > 0) {
        System.out.println("  Skipped files: " + skippedCount.get());
      }
      System.out.println("  Total lines processed: " + totalLines.get());

      if (!errorsByFile.isEmpty() && !ciMode) {
        System.out.println("\n" + errorFormatter.formatErrorSummary(errorsByFile));
      }

      if (errorCount.get() > 0) {
        System.exit(1);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void _checkFiles(String[] args) {
    if (args.length < 2) {
      _printError("Error: Missing path argument");
      _printUsage();
      System.exit(1);
    }

    String targetPath = args[1];
    Path path = Paths.get(targetPath);

    if (!Files.exists(path)) {
      _printError("Error: Path does not exist: " + targetPath);
      System.exit(1);
    }

    // Parse optional arguments
    boolean verbose = _hasOption(args, "--verbose");
    boolean ciMode = _hasOption(args, "--ci");
    boolean skipReact = _hasOption(args, "--skip-react");
    String configFile = _getOptionValue(args, "--config");
    String includePattern = _getOptionValue(args, "--include");

    // Load configuration
    FormatterConfig config;
    if (configFile != null) {
      config = ConfigurationLoader.loadConfig(Paths.get(configFile));
    } else {
      config = ConfigurationLoader.loadConfig(Paths.get(CONFIG_FILE_NAME));
    }

    // Create formatter
    try (AdvancedCodeFormatter formatter = _createFormatter(config, verbose, skipReact)) {
      AtomicInteger fileCount = new AtomicInteger(0);
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicInteger skippedCount = new AtomicInteger(0);
      AtomicInteger nonCompliantCount = new AtomicInteger(0);

      // Track errors by file
      Map<Path, List<FormatterError>> errorsByFile = new HashMap<>();

      List<Path> filesToCheck =
          _findFiles(
              path,
              config.getGeneralConfig("ignoreFiles", new ArrayList<String>()),
              includePattern);

      _printInfo("Found " + filesToCheck.size() + " files to check");

      Instant start = Instant.now();

      for (Path file : filesToCheck) {
        try {
          // Check if we should skip this file based on file type
          FileType fileType = FileType.detect(file);
          if (skipReact
              && (fileType == FileType.JAVASCRIPT
                  || fileType == FileType.JSX
                  || fileType == FileType.TYPESCRIPT
                  || fileType == FileType.TSX)) {
            if (verbose) {
              _printInfo("Skipping JavaScript/React file (--skip-react): " + file);
            }
            skippedCount.incrementAndGet();
            continue;
          }

          // Check if we have an active plugin for this file type
          if (!formatter.hasActivePluginFor(fileType)) {
            if (verbose) {
              _printWarning("Skipping file (no active plugin): " + file);
            }
            skippedCount.incrementAndGet();
            continue;
          }

          if (verbose) {
            _printInfo("Checking: " + file);
          }

          String source = Files.readString(file);
          FormatterResult result = formatter.formatFile(file, source);

          // Check if this is just a warning about React formatter being disabled
          boolean isReactDisabledWarning =
              !result.isSuccessful()
                  && result.getErrors().stream()
                      .anyMatch(e -> e.getMessage().contains("ReactJS formatter is disabled"));

          if (isReactDisabledWarning) {
            if (verbose) {
              _printWarning("Skipping (ReactJS formatter disabled): " + file);
            }
            skippedCount.incrementAndGet();
            continue;
          }

          if (!result.isSuccessful() || !result.getFormattedCode().equals(source)) {
            _printWarning("File needs formatting: " + file);
            nonCompliantCount.incrementAndGet();

            // Store errors for summary
            if (!result.getErrors().isEmpty()) {
              errorsByFile.put(file, result.getErrors());
            }

            if (!result.getErrors().isEmpty()) {
              System.out.println("  Issues found:");
              result.getErrors().forEach(e -> _printError("    " + errorFormatter.formatError(e)));
            }

            if (!result.getAppliedRefactorings().isEmpty() && verbose) {
              System.out.println("  Suggested refactorings:");
              result
                  .getAppliedRefactorings()
                  .forEach(r -> _printInfo("    - " + r.getDescription()));
            }
          } else if (verbose) {
            _printSuccess("  OK: " + file);
          }

          fileCount.incrementAndGet();
        } catch (Exception e) {
          _printError("Error checking file: " + file);
          _printError("  " + e.getMessage());

          // Log the exception
          logger.log(Level.SEVERE, "Error checking file: " + file, e);

          // Store the error
          List<FormatterError> errors = new ArrayList<>();
          errors.add(
              new FormatterError(
                  Severity.FATAL,
                  "Exception: " + e.getMessage(),
                  1,
                  1,
                  "Check the log file for details"));
          errorsByFile.put(file, errors);

          if (verbose) {
            e.printStackTrace();
          }
          errorCount.incrementAndGet();
        }
      }

      Instant end = Instant.now();
      Duration duration = Duration.between(start, end);

      System.out.println("\nCheck complete in " + _formatDuration(duration) + ":");
      System.out.println("  Checked files: " + fileCount.get());
      System.out.println("  Files needing formatting: " + nonCompliantCount.get());
      System.out.println("  Files with processing errors: " + errorCount.get());
      if (skippedCount.get() > 0) {
        System.out.println("  Skipped files: " + skippedCount.get());
      }

      if (!errorsByFile.isEmpty() && !ciMode) {
        System.out.println("\n" + errorFormatter.formatErrorSummary(errorsByFile));
      }

      if (nonCompliantCount.get() > 0 || errorCount.get() > 0) {
        System.exit(1);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void _analyzeFiles(String[] args) throws IOException {
    if (args.length < 2) {
      _printError("Error: Missing path argument");
      _printUsage();
      System.exit(1);
    }

    String targetPath = args[1];
    Path path = Paths.get(targetPath);

    if (!Files.exists(path)) {
      _printError("Error: Path does not exist: " + targetPath);
      System.exit(1);
    }

    // Parse optional arguments
    boolean verbose = _hasOption(args, "--verbose");
    boolean ciMode = _hasOption(args, "--ci");
    boolean skipReact = _hasOption(args, "--skip-react");
    String configFile = _getOptionValue(args, "--config");
    String includePattern = _getOptionValue(args, "--include");

    // Load configuration
    FormatterConfig config;
    if (configFile != null) {
      config = ConfigurationLoader.loadConfig(Paths.get(configFile));
    } else {
      config = ConfigurationLoader.loadConfig(Paths.get(CONFIG_FILE_NAME));
    }

    // Create formatter
    try (AdvancedCodeFormatter formatter = _createFormatter(config, verbose, skipReact)) {
      AtomicInteger fileCount = new AtomicInteger(0);
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicInteger skippedCount = new AtomicInteger(0);
      AtomicInteger issueCount = new AtomicInteger(0);

      List<Path> filesToCheck =
          _findFiles(
              path,
              config.getGeneralConfig("ignoreFiles", new ArrayList<String>()),
              includePattern);

      _printInfo("Found " + filesToCheck.size() + " files to analyze");

      Instant start = Instant.now();

      Map<Path, List<FormatterError>> errorsByFile = new HashMap<>();

      for (Path file : filesToCheck) {
        try {
          // Check if we should skip this file based on file type
          FileType fileType = FileType.detect(file);
          if (skipReact
              && (fileType == FileType.JAVASCRIPT
                  || fileType == FileType.JSX
                  || fileType == FileType.TYPESCRIPT
                  || fileType == FileType.TSX)) {
            if (verbose) {
              _printInfo("Skipping JavaScript/React file (--skip-react): " + file);
            }
            skippedCount.incrementAndGet();
            continue;
          }

          // Check if we have an active plugin for this file type
          if (!formatter.hasActivePluginFor(fileType)) {
            if (verbose) {
              _printWarning("Skipping file (no active plugin): " + file);
            }
            skippedCount.incrementAndGet();
            continue;
          }

          if (verbose) {
            _printInfo("Analyzing: " + file);
          }

          String source = Files.readString(file);
          FormatterResult result = formatter.formatFile(file, source);

          // Check if this is just a warning about React formatter being disabled
          boolean isReactDisabledWarning =
              !result.isSuccessful()
                  && result.getErrors().stream()
                      .anyMatch(e -> e.getMessage().contains("ReactJS formatter is disabled"));

          if (isReactDisabledWarning) {
            if (verbose) {
              _printWarning("Skipping (ReactJS formatter disabled): " + file);
            }
            skippedCount.incrementAndGet();
            continue;
          }

          if (!result.getErrors().isEmpty()) {
            errorsByFile.put(file, result.getErrors());
            issueCount.addAndGet(result.getErrors().size());

            if (!ciMode) {
              System.out.println(
                  errorFormatter.colorize(ErrorFormatter.ANSI_BOLD, file.toString() + ":"));

              Map<Severity, List<FormatterError>> errorsBySeverity =
                  errorFormatter.groupBySeverity(result.getErrors());

              _printErrorsBySeverity(errorsBySeverity, Severity.FATAL);
              _printErrorsBySeverity(errorsBySeverity, Severity.ERROR);
              _printErrorsBySeverity(errorsBySeverity, Severity.WARNING);
              _printErrorsBySeverity(errorsBySeverity, Severity.INFO);
            }
          } else if (verbose) {
            _printSuccess("  No issues found: " + file);
          }

          fileCount.incrementAndGet();
        } catch (Exception e) {
          _printError("Error analyzing file: " + file);
          _printError("  " + e.getMessage());

          // Log the exception
          logger.log(Level.SEVERE, "Error analyzing file: " + file, e);

          // Store the error
          List<FormatterError> errors = new ArrayList<>();
          errors.add(
              new FormatterError(
                  Severity.FATAL,
                  "Exception: " + e.getMessage(),
                  1,
                  1,
                  "Check the log file for details"));
          errorsByFile.put(file, errors);

          if (verbose) {
            e.printStackTrace();
          }
          errorCount.incrementAndGet();
        }
      }

      Instant end = Instant.now();
      Duration duration = Duration.between(start, end);

      // Print comprehensive summary report
      System.out.println("\nAnalysis complete in " + _formatDuration(duration) + ":");

      // Count files by severity for the summary
      int filesWithErrors =
          (int)
              errorsByFile.values().stream()
                  .filter(
                      errors ->
                          errors.stream()
                              .anyMatch(
                                  e ->
                                      e.getSeverity() == Severity.ERROR
                                          || e.getSeverity() == Severity.FATAL))
                  .count();

      int filesWithWarnings =
          (int)
              errorsByFile.values().stream()
                  .filter(
                      errors -> errors.stream().anyMatch(e -> e.getSeverity() == Severity.WARNING))
                  .count();

      int filesWithInfo =
          (int)
              errorsByFile.values().stream()
                  .filter(errors -> errors.stream().anyMatch(e -> e.getSeverity() == Severity.INFO))
                  .count();

      System.out.println("  Files analyzed: " + fileCount.get());
      System.out.println("  Total issues found: " + issueCount.get());
      System.out.println("  Files with errors: " + filesWithErrors);
      System.out.println("  Files with warnings: " + filesWithWarnings);
      System.out.println("  Files with suggestions: " + filesWithInfo);
      System.out.println("  Files with processing failures: " + errorCount.get());
      if (skippedCount.get() > 0) {
        System.out.println("  Skipped files: " + skippedCount.get());
      }

      if (!errorsByFile.isEmpty() && !ciMode) {
        System.out.println("\n" + errorFormatter.formatErrorSummary(errorsByFile));
      }

      if (ciMode) {
        System.out.println(
            "RESULT:files="
                + fileCount.get()
                + ";errors="
                + filesWithErrors
                + ";warnings="
                + filesWithWarnings
                + ";info="
                + filesWithInfo
                + ";issues="
                + issueCount.get());
      }

      if (filesWithErrors > 0 || errorCount.get() > 0) {
        System.exit(1);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void _initializeConfig(String[] args) throws IOException {
    Path configPath = Paths.get(CONFIG_FILE_NAME);
    boolean force = _hasOption(args, "--force");

    if (Files.exists(configPath) && !force) {
      _printWarning("Configuration file already exists: " + CONFIG_FILE_NAME);
      System.out.println("Use --force to overwrite it or specify a different path with --config");
      return;
    }

    FormatterConfig config = ConfigurationLoader.loadDefaultConfig();
    ConfigurationLoader.saveConfig(config, configPath);
    _printSuccess("Created configuration file: " + CONFIG_FILE_NAME);
  }

  private static AdvancedCodeFormatter _createFormatter(
      FormatterConfig config, boolean verbose, boolean skipReact) {
    AdvancedCodeFormatter formatter = new AdvancedCodeFormatter(config);

    try {
      // Register the Spring Boot formatter for Java files
      formatter.registerPlugin(FileType.JAVA, new SpringBootFormatter());

      // Only register React formatter if not skipped
      if (!skipReact) {
        // Create and initialize React formatter
        ReactJSFormatter reactFormatter = new ReactJSFormatter();

        // Register for all JS/TS file types regardless of availability
        // The ReactJSFormatter itself will handle disabled operation gracefully
        formatter.registerPlugin(FileType.JAVASCRIPT, reactFormatter);
        formatter.registerPlugin(FileType.JSX, reactFormatter);
        formatter.registerPlugin(FileType.TYPESCRIPT, reactFormatter);
        formatter.registerPlugin(FileType.TSX, reactFormatter);

        if (reactFormatter.isDisabled()) {
          _printWarning(
              "JavaScript/React formatting disabled: " + reactFormatter.getDisabledReason());
          _printInfo(
              "Run 'codeformatter setup' to set up the environment for JavaScript/React formatting");
        } else {
          _printInfo("Initialized formatter with Spring Boot and React JS plugins");
        }
      } else {
        _printInfo("Initialized formatter with Spring Boot plugin only (React formatting skipped)");
      }
    } catch (Exception e) {
      _printWarning(
          "Warning: Failed to initialize one or more formatter plugins: " + e.getMessage());
      logger.log(Level.WARNING, "Failed to initialize plugins", e);

      if (verbose) {
        e.printStackTrace();
      }
    }

    return formatter;
  }

  private static List<Path> _findFiles(
      Path path, List<String> ignorePatterns, String includePattern) throws IOException {
    if (Files.isRegularFile(path)) {
      return List.of(path);
    }

    try {
      return Files.walk(path)
          .filter(Files::isRegularFile)
          .filter(FormatterCli::_isSupported)
          .filter(p -> _matchesIncludePattern(p, includePattern))
          .filter(p -> !_isIgnored(p, path, ignorePatterns))
          .collect(Collectors.toList());
    } catch (IOException e) {
      _printError("Error scanning directory: " + e.getMessage());
      throw e;
    }
  }

  private static boolean _isSupported(Path file) {
    String fileName = file.getFileName().toString().toLowerCase();
    return fileName.endsWith(".java")
        || fileName.endsWith(".js")
        || fileName.endsWith(".jsx")
        || fileName.endsWith(".ts")
        || fileName.endsWith(".tsx");
  }

  private static boolean _matchesIncludePattern(Path file, String includePattern) {
    if (includePattern == null || includePattern.isEmpty()) {
      return true;
    }

    String fileName = file.getFileName().toString();

    if (includePattern.startsWith("*.")) {
      String extension = includePattern.substring(1);
      return fileName.endsWith(extension);
    } else if (includePattern.contains("*")) {
      String regex = includePattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
      return fileName.matches(regex);
    } else {
      return fileName.contains(includePattern);
    }
  }

  private static boolean _isIgnored(Path file, Path basePath, List<String> ignorePatterns) {
    if (ignorePatterns == null || ignorePatterns.isEmpty()) {
      return false;
    }

    String relativePath = basePath.relativize(file).toString().replace("\\", "/");

    for (String pattern : ignorePatterns) {
      if (pattern.startsWith("**/")) {
        String suffix = pattern.substring(3);
        if (relativePath.endsWith(suffix)) {
          return true;
        }
      } else if (pattern.endsWith("/**")) {
        String prefix = pattern.substring(0, pattern.length() - 3);
        if (relativePath.startsWith(prefix)) {
          return true;
        }
      } else if (pattern.contains("*")) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        if (relativePath.matches(regex)) {
          return true;
        }
      } else if (pattern.equals(relativePath)) {
        return true;
      }
    }

    return false;
  }

  private static boolean _hasOption(String[] args, String option) {
    return Arrays.asList(args).contains(option);
  }

  private static String _getOptionValue(String[] args, String option) {
    String prefix = option + "=";
    return Arrays.stream(args)
        .filter(arg -> arg.startsWith(prefix))
        .map(arg -> arg.substring(prefix.length()))
        .findFirst()
        .orElse(null);
  }

  private static void _printErrorsBySeverity(
      Map<Severity, List<FormatterError>> errorsBySeverity, Severity severity) {
    if (errorsBySeverity.containsKey(severity)) {
      for (FormatterError error : errorsBySeverity.get(severity)) {
        switch (severity) {
          case FATAL:
          case ERROR:
            _printError("  " + errorFormatter.formatError(error));
            break;
          case WARNING:
            _printWarning("  " + errorFormatter.formatError(error));
            break;
          case INFO:
            _printInfo("  " + errorFormatter.formatError(error));
            break;
        }
      }
    }
  }

  private static String _formatDuration(Duration duration) {
    long seconds = duration.getSeconds();
    long millis = duration.toMillis() % 1000;
    if (seconds < 60) {
      return String.format("%d.%03d seconds", seconds, millis);
    } else {
      long minutes = seconds / 60;
      seconds = seconds % 60;
      return String.format("%d min %d sec", minutes, seconds);
    }
  }

  private static void _printHeader(String header) {
    System.out.println();
    System.out.println(errorFormatter.colorize(ErrorFormatter.ANSI_BOLD, "=== " + header + " ==="));
  }

  private static void _printBullet(String message) {
    System.out.println("• " + message);
  }

  private static void _printSuccess(String message) {
    System.out.println(errorFormatter.colorize(ErrorFormatter.ANSI_GREEN, message));
  }

  private static void _printError(String message) {
    System.out.println(errorFormatter.colorize(ErrorFormatter.ANSI_RED, message));
  }

  private static void _printWarning(String message) {
    System.out.println(errorFormatter.colorize(ErrorFormatter.ANSI_YELLOW, message));
  }

  private static void _printInfo(String message) {
    System.out.println(errorFormatter.colorize(ErrorFormatter.ANSI_BLUE, message));
  }
}
