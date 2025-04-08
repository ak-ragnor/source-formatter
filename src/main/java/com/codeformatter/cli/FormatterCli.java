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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Enhanced Command Line Interface for the Advanced Code Formatter
 */
public class FormatterCli {

    private static final String VERSION = "1.0.0";
    private static final String CONFIG_FILE_NAME = ".codeformatter.yml";

    // ANSI colors for terminal output (can be disabled with --no-color)
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_BOLD = "\u001B[1m";

    private static boolean useColors = true;

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                _printUsage();
                System.exit(1);
            }

            // Check for color disabling early
            useColors = !_hasOption(args, "--no-color");

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
            if (_hasOption(args, "--verbose")) {
                e.printStackTrace();
            } else {
                _printInfo("Use --verbose for stack trace");
            }
            System.exit(1);
        }
    }

    private static void _printVersion() {
        System.out.println("Advanced Code Formatter version " + VERSION);
    }

    private static void _printUsage() {
        System.out.println(_colorize(ANSI_BOLD, "Advanced Code Formatter CLI v" + VERSION));
        System.out.println("Usage:");
        System.out.println("  codeformatter init [--force]      - Initialize configuration file");
        System.out.println("  codeformatter format <path>       - Format files in path");
        System.out.println("  codeformatter check <path>        - Check files without formatting");
        System.out.println("  codeformatter analyze <path>      - Analyze code without formatting");
        System.out.println("  codeformatter --help|-h           - Show this help");
        System.out.println("  codeformatter --version|-v        - Show version information");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --config=<file>                   - Use specific config file (default: .codeformatter.yml)");
        System.out.println("  --verbose                         - Show detailed output");
        System.out.println("  --ci                              - CI friendly output (simplified)");
        System.out.println("  --no-color                        - Disable colored output");
        System.out.println("  --include=<glob>                  - Only include files matching pattern");
        System.out.println("  --threads=<num>                   - Number of threads to use (default: available processors)");
        System.out.println("  --force                           - Force overwrite (with init command)");
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

        // Parse optional arguments
        boolean verbose = _hasOption(args, "--verbose");
        boolean ciMode = _hasOption(args, "--ci");
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

        // Load configuration
        FormatterConfig config;
        if (configFile != null) {
            _printInfo("Using config file: " + configFile);
            config = ConfigurationLoader.loadConfig(Paths.get(configFile));
        } else {
            config = ConfigurationLoader.loadConfig(Paths.get(CONFIG_FILE_NAME));
        }

        // Create and configure formatter
        try (AdvancedCodeFormatter formatter = _createFormatter(config, verbose)) {
            AtomicInteger fileCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            AtomicInteger skippedCount = new AtomicInteger(0);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicLong totalLines = new AtomicLong(0);

            // Find files to format
            List<Path> filesToFormat = _findFiles(path,
                    config.getGeneralConfig("ignoreFiles", new ArrayList<String>()),
                    includePattern);

            _printInfo("Found " + filesToFormat.size() + " files to format");

            Instant start = Instant.now();

            // Process each file
            for (Path file : filesToFormat) {
                try {
                    if (verbose) {
                        _printInfo("Processing: " + file);
                    }

                    String source = Files.readString(file);
                    totalLines.addAndGet(source.split("\n").length);

                    FormatterResult result = formatter.formatFile(file, source);

                    if (result.isSuccessful()) {
                        // Only write if content actually changed
                        if (!source.equals(result.getFormattedCode())) {
                            Files.writeString(file, result.getFormattedCode());

                            _printSuccess("Formatted: " + file);
                            successCount.incrementAndGet();

                            if (!ciMode && !result.getAppliedRefactorings().isEmpty() && verbose) {
                                _printInfo("  Applied refactorings:");
                                result.getAppliedRefactorings().forEach(r ->
                                        _printInfo("    - " + r.getDescription()));
                            }
                        } else {
                            if (verbose) {
                                _printInfo("  Already formatted: " + file);
                            }
                            successCount.incrementAndGet();
                        }
                    } else {
                        _printError("Failed to format: " + file);
                        result.getErrors().forEach(e ->
                                _printError("  " + _formatError(e)));
                        errorCount.incrementAndGet();
                    }

                    fileCount.incrementAndGet();
                } catch (Exception e) {
                    _printError("Error processing file: " + file);
                    _printError("  " + e.getMessage());
                    if (verbose) {
                        e.printStackTrace();
                    }
                    errorCount.incrementAndGet();
                }
            }

            Instant end = Instant.now();
            Duration duration = Duration.between(start, end);

            // Print summary
            System.out.println("\nFormatting complete in " + _formatDuration(duration) + ":");
            System.out.println("  Processed files: " + fileCount.get());
            System.out.println("  Successfully formatted: " + successCount.get());
            System.out.println("  Files with errors: " + errorCount.get());
            if (skippedCount.get() > 0) {
                System.out.println("  Skipped files: " + skippedCount.get());
            }
            System.out.println("  Total lines processed: " + totalLines.get());

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
        try (AdvancedCodeFormatter formatter = _createFormatter(config, verbose)) {
            AtomicInteger fileCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            AtomicInteger skippedCount = new AtomicInteger(0);
            AtomicInteger nonCompliantCount = new AtomicInteger(0);

            List<Path> filesToCheck = _findFiles(path,
                    config.getGeneralConfig("ignoreFiles", new ArrayList<String>()),
                    includePattern);

            _printInfo("Found " + filesToCheck.size() + " files to check");

            Instant start = Instant.now();

            for (Path file : filesToCheck) {
                try {
                    if (verbose) {
                        _printInfo("Checking: " + file);
                    }

                    String source = Files.readString(file);
                    FormatterResult result = formatter.formatFile(file, source);

                    if (!result.isSuccessful() || !result.getFormattedCode().equals(source)) {
                        _printWarning("File needs formatting: " + file);
                        nonCompliantCount.incrementAndGet();

                        if (!result.getErrors().isEmpty()) {
                            System.out.println("  Issues found:");
                            result.getErrors().forEach(e ->
                                    _printError("    " + _formatError(e)));
                        }

                        if (!result.getAppliedRefactorings().isEmpty() && verbose) {
                            System.out.println("  Suggested refactorings:");
                            result.getAppliedRefactorings().forEach(r ->
                                    _printInfo("    - " + r.getDescription()));
                        }
                    } else if (verbose) {
                        _printSuccess("  OK: " + file);
                    }

                    fileCount.incrementAndGet();
                } catch (Exception e) {
                    _printError("Error checking file: " + file);
                    _printError("  " + e.getMessage());
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

            if (nonCompliantCount.get() > 0 || errorCount.get() > 0) {
                System.exit(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * New command that only analyzes code without formatting it
     */
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
        try (AdvancedCodeFormatter formatter = _createFormatter(config, verbose)) {
            AtomicInteger fileCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            AtomicInteger issueCount = new AtomicInteger(0);

            List<Path> filesToCheck = _findFiles(path,
                    config.getGeneralConfig("ignoreFiles", new ArrayList<String>()),
                    includePattern);

            _printInfo("Found " + filesToCheck.size() + " files to analyze");

            Instant start = Instant.now();

            // Track files by issue severity for reporting
            List<Path> filesWithErrors = new ArrayList<>();
            List<Path> filesWithWarnings = new ArrayList<>();
            List<Path> filesWithInfo = new ArrayList<>();

            for (Path file : filesToCheck) {
                try {
                    if (verbose) {
                        _printInfo("Analyzing: " + file);
                    }

                    String source = Files.readString(file);
                    FormatterResult result = formatter.formatFile(file, source);

                    // Determine if file has issues by severity
                    boolean hasErrors = result.getErrors().stream()
                            .anyMatch(e -> e.getSeverity() == Severity.ERROR || e.getSeverity() == Severity.FATAL);
                    boolean hasWarnings = result.getErrors().stream()
                            .anyMatch(e -> e.getSeverity() == Severity.WARNING);
                    boolean hasInfo = result.getErrors().stream()
                            .anyMatch(e -> e.getSeverity() == Severity.INFO);

                    // Add to appropriate lists for summary
                    if (hasErrors) filesWithErrors.add(file);
                    if (hasWarnings) filesWithWarnings.add(file);
                    if (hasInfo) filesWithInfo.add(file);

                    // Print issues if found
                    if (!result.getErrors().isEmpty()) {
                        System.out.println(_colorize(ANSI_BOLD, file.toString() + ":"));
                        // Group issues by severity for better readability
                        Map<Severity, List<FormatterError>> errorsBySeverity = result.getErrors().stream()
                                .collect(Collectors.groupingBy(FormatterError::getSeverity));

                        // Print errors first, then warnings, then info
                        _printErrorsBySeverity(errorsBySeverity, Severity.FATAL);
                        _printErrorsBySeverity(errorsBySeverity, Severity.ERROR);
                        _printErrorsBySeverity(errorsBySeverity, Severity.WARNING);
                        _printErrorsBySeverity(errorsBySeverity, Severity.INFO);

                        issueCount.addAndGet(result.getErrors().size());
                    } else if (verbose) {
                        _printSuccess("  No issues found: " + file);
                    }

                    fileCount.incrementAndGet();
                } catch (Exception e) {
                    _printError("Error analyzing file: " + file);
                    _printError("  " + e.getMessage());
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
            System.out.println("  Files analyzed: " + fileCount.get());
            System.out.println("  Total issues found: " + issueCount.get());
            System.out.println("  Files with errors: " + filesWithErrors.size());
            System.out.println("  Files with warnings: " + filesWithWarnings.size());
            System.out.println("  Files with suggestions: " + filesWithInfo.size());
            System.out.println("  Files with processing failures: " + errorCount.get());

            // Exit with error code if issues were found
            if (!filesWithErrors.isEmpty() || errorCount.get() > 0) {
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

        // Let the configuration loader generate a default config
        FormatterConfig config = ConfigurationLoader.loadDefaultConfig();
        ConfigurationLoader.saveConfig(config, configPath);
        _printSuccess("Created configuration file: " + CONFIG_FILE_NAME);
    }

    private static AdvancedCodeFormatter _createFormatter(FormatterConfig config, boolean verbose) {
        AdvancedCodeFormatter formatter = new AdvancedCodeFormatter(config);

        try {
            formatter.registerPlugin(FileType.JAVA, new SpringBootFormatter());
            formatter.registerPlugin(FileType.JAVASCRIPT, new ReactJSFormatter());
            formatter.registerPlugin(FileType.JSX, new ReactJSFormatter());
            formatter.registerPlugin(FileType.TYPESCRIPT, new ReactJSFormatter());
            formatter.registerPlugin(FileType.TSX, new ReactJSFormatter());
            _printInfo("Initialized formatter with Spring Boot and React JS plugins");
        } catch (Exception e) {
            _printWarning("Warning: Failed to initialize one or more formatter plugins: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
        }

        return formatter;
    }

    private static List<Path> _findFiles(Path path, List<String> ignorePatterns, String includePattern) throws IOException {
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
        return fileName.endsWith(".java") ||
                fileName.endsWith(".js") ||
                fileName.endsWith(".jsx") ||
                fileName.endsWith(".ts") ||
                fileName.endsWith(".tsx");
    }

    private static boolean _matchesIncludePattern(Path file, String includePattern) {
        if (includePattern == null || includePattern.isEmpty()) {
            return true; // No include pattern specified, include all
        }

        String fileName = file.getFileName().toString();
        // Simple glob implementation - in a real tool use java.nio.file.PathMatcher
        if (includePattern.startsWith("*.")) {
            String extension = includePattern.substring(1); // *.java -> .java
            return fileName.endsWith(extension);
        } else if (includePattern.contains("*")) {
            // Convert glob pattern to regex
            String regex = includePattern
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".");
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
            // Enhanced glob pattern matching
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
                // Convert glob pattern to regex
                String regex = pattern
                        .replace(".", "\\.")
                        .replace("*", ".*")
                        .replace("?", ".");
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

    private static void _printErrorsBySeverity(Map<Severity, List<FormatterError>> errorsBySeverity, Severity severity) {
        if (errorsBySeverity.containsKey(severity)) {
            for (FormatterError error : errorsBySeverity.get(severity)) {
                switch (severity) {
                    case FATAL:
                    case ERROR:
                        _printError("  " + _formatError(error));
                        break;
                    case WARNING:
                        _printWarning("  " + _formatError(error));
                        break;
                    case INFO:
                        _printInfo("  " + _formatError(error));
                        break;
                }
            }
        }
    }

    private static String _formatError(FormatterError error) {
        StringBuilder sb = new StringBuilder();
        sb.append(error.getSeverity()).append(": ");
        sb.append(error.getMessage());
        sb.append(" (Line ").append(error.getLine()).append(")");
        if (error.getSuggestion() != null && !error.getSuggestion().isEmpty()) {
            sb.append(" - ").append(error.getSuggestion());
        }
        return sb.toString();
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

    private static void _printSuccess(String message) {
        System.out.println(_colorize(ANSI_GREEN, message));
    }

    private static void _printError(String message) {
        System.out.println(_colorize(ANSI_RED, message));
    }

    private static void _printWarning(String message) {
        System.out.println(_colorize(ANSI_YELLOW, message));
    }

    private static void _printInfo(String message) {
        System.out.println(_colorize(ANSI_BLUE, message));
    }

    private static String _colorize(String color, String message) {
        if (useColors) {
            return color + message + ANSI_RESET;
        }
        return message;
    }
}