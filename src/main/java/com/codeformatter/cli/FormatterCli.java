package com.codeformatter.cli;

import com.codeformatter.api.FormatterResult;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.core.AdvancedCodeFormatter;
import com.codeformatter.plugins.FileType;
import com.codeformatter.plugins.react.ReactJSFormatter;
import com.codeformatter.plugins.spring.SpringBootFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Command Line Interface for the Advanced Code Formatter
 */
public class FormatterCli {

    private static final String CONFIG_FILE_NAME = ".codeformatter.yml";
    private static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                printUsage();
                System.exit(1);
            }

            String command = args[0];

            switch (command) {
                case "format":
                    formatFiles(args);
                    break;
                case "check":
                    checkFiles(args);
                    break;
                case "init":
                    initializeConfig();
                    break;
                case "--version":
                case "-v":
                    printVersion();
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    break;
                default:
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printVersion() {
        System.out.println("Advanced Code Formatter version " + VERSION);
    }

    private static void printUsage() {
        System.out.println("Advanced Code Formatter CLI v" + VERSION);
        System.out.println("Usage:");
        System.out.println("  codeformatter init                 - Initialize configuration file");
        System.out.println("  codeformatter format <path>        - Format files in path");
        System.out.println("  codeformatter check <path>         - Check files without formatting");
        System.out.println("  codeformatter --help|-h            - Show this help");
        System.out.println("  codeformatter --version|-v         - Show version information");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --config=<file>                    - Use specific config file (default: .codeformatter.yml)");
        System.out.println("  --verbose                          - Show detailed output");
        System.out.println("  --ci                               - CI friendly output (no colors, simplified)");
    }

    private static void formatFiles(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Error: Missing path argument");
            printUsage();
            System.exit(1);
        }

        String targetPath = args[1];
        Path path = Paths.get(targetPath);

        if (!Files.exists(path)) {
            System.err.println("Error: Path does not exist: " + targetPath);
            System.exit(1);
        }

        // Parse optional arguments
        boolean verbose = hasOption(args, "--verbose");
        boolean ciMode = hasOption(args, "--ci");
        String configFile = getOptionValue(args, "--config");

        FormatterConfig config;
        if (configFile != null) {
            config = loadConfig(Paths.get(configFile));
        } else {
            config = loadConfig();
        }

        AdvancedCodeFormatter formatter = createFormatter(config, verbose);

        AtomicInteger fileCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);

        List<Path> filesToFormat = findFiles(path, config.getGeneralConfig("ignoreFiles", new ArrayList<String>()));
        System.out.println("Found " + filesToFormat.size() + " files to format");

        for (Path file : filesToFormat) {
            try {
                if (verbose) {
                    System.out.println("Processing: " + file);
                }

                String source = Files.readString(file);
                FormatterResult result = formatter.formatFile(file, source);

                if (result.isSuccessful()) {
                    Files.writeString(file, result.getFormattedCode());

                    if (!ciMode) {
                        System.out.println("Formatted: " + file);

                        if (!result.getAppliedRefactorings().isEmpty()) {
                            System.out.println("  Applied refactorings:");
                            result.getAppliedRefactorings().forEach(r ->
                                    System.out.println("    - " + r.getDescription()));
                        }
                    }
                } else {
                    System.err.println("Failed to format: " + file);
                    result.getErrors().forEach(e ->
                            System.err.println("  - " + e.getSeverity() + ": " + e.getMessage() +
                                    " (Line " + e.getLine() + ")"));
                    errorCount.incrementAndGet();
                }

                fileCount.incrementAndGet();
            } catch (Exception e) {
                System.err.println("Error processing file: " + file);
                System.err.println("  " + e.getMessage());
                if (verbose) {
                    e.printStackTrace();
                }
                errorCount.incrementAndGet();
            }
        }

        System.out.println("\nFormatting complete:");
        System.out.println("  Processed files: " + fileCount.get());
        System.out.println("  Files with errors: " + errorCount.get());
        if (skippedCount.get() > 0) {
            System.out.println("  Skipped files: " + skippedCount.get());
        }

        if (errorCount.get() > 0) {
            System.exit(1);
        }
    }

    private static void checkFiles(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Error: Missing path argument");
            printUsage();
            System.exit(1);
        }

        String targetPath = args[1];
        Path path = Paths.get(targetPath);

        if (!Files.exists(path)) {
            System.err.println("Error: Path does not exist: " + targetPath);
            System.exit(1);
        }

        // Parse optional arguments
        boolean verbose = hasOption(args, "--verbose");
        boolean ciMode = hasOption(args, "--ci");
        String configFile = getOptionValue(args, "--config");

        FormatterConfig config;
        if (configFile != null) {
            config = loadConfig(Paths.get(configFile));
        } else {
            config = loadConfig();
        }

        AdvancedCodeFormatter formatter = createFormatter(config, verbose);

        AtomicInteger fileCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);

        List<Path> filesToCheck = findFiles(path, config.getGeneralConfig("ignoreFiles", new ArrayList<String>()));
        System.out.println("Found " + filesToCheck.size() + " files to check");

        for (Path file : filesToCheck) {
            try {
                if (verbose) {
                    System.out.println("Checking: " + file);
                }

                String source = Files.readString(file);
                FormatterResult result = formatter.formatFile(file, source);

                if (!result.isSuccessful() || !result.getFormattedCode().equals(source)) {
                    System.out.println("File needs formatting: " + file);

                    if (!result.getErrors().isEmpty()) {
                        System.out.println("  Issues found:");
                        result.getErrors().forEach(e ->
                                System.out.println("    - " + e.getSeverity() + ": " + e.getMessage() +
                                        " (Line " + e.getLine() + ")"));
                    }

                    if (!result.getAppliedRefactorings().isEmpty()) {
                        System.out.println("  Suggested refactorings:");
                        result.getAppliedRefactorings().forEach(r ->
                                System.out.println("    - " + r.getDescription()));
                    }

                    errorCount.incrementAndGet();
                } else if (verbose) {
                    System.out.println("  OK: " + file);
                }

                fileCount.incrementAndGet();
            } catch (Exception e) {
                System.err.println("Error checking file: " + file);
                System.err.println("  " + e.getMessage());
                if (verbose) {
                    e.printStackTrace();
                }
                errorCount.incrementAndGet();
            }
        }

        System.out.println("\nCheck complete:");
        System.out.println("  Checked files: " + fileCount.get());
        System.out.println("  Files needing formatting: " + errorCount.get());
        if (skippedCount.get() > 0) {
            System.out.println("  Skipped files: " + skippedCount.get());
        }

        if (errorCount.get() > 0) {
            System.exit(1);
        }
    }

    private static void initializeConfig() throws IOException {
        Path configPath = Paths.get(CONFIG_FILE_NAME);

        if (Files.exists(configPath)) {
            System.out.println("Configuration file already exists: " + CONFIG_FILE_NAME);
            System.out.println("Do you want to overwrite it? (y/n)");

            int input = System.in.read();
            if (input != 'y' && input != 'Y') {
                System.out.println("Aborted");
                return;
            }
        }

        String defaultConfig;
        try (InputStream defaultConfigStream = FormatterCli.class.getResourceAsStream("/config/default-config.yml")) {
            if (defaultConfigStream == null) {
                System.err.println("Error: Could not load default configuration");
                System.exit(1);
            }

            defaultConfig = new String(defaultConfigStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.writeString(configPath, defaultConfig);
        System.out.println("Created configuration file: " + CONFIG_FILE_NAME);
    }


    private static FormatterConfig loadConfig() throws IOException {
        return loadConfig(Paths.get(CONFIG_FILE_NAME));
    }

    private static FormatterConfig loadConfig(Path configPath) throws IOException {
        if (!Files.exists(configPath)) {
            System.out.println("Configuration file not found: " + configPath);
            System.out.println("Using default configuration");
            return createDefaultConfig();
        }

        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> config = mapper.readValue(configPath.toFile(), Map.class);

            Map<String, Object> generalConfig = (Map<String, Object>) config.getOrDefault("general", new HashMap<>());
            Map<String, Map<String, Object>> pluginConfigs = (Map<String, Map<String, Object>>)
                    config.getOrDefault("plugins", new HashMap<>());

            return new FormatterConfig(generalConfig, pluginConfigs);
        } catch (Exception e) {
            System.err.println("Error parsing configuration file: " + e.getMessage());
            System.out.println("Using default configuration");
            return createDefaultConfig();
        }
    }

    private static FormatterConfig createDefaultConfig() {
        Map<String, Object> generalConfig = new HashMap<>();
        generalConfig.put("indentSize", 4);
        generalConfig.put("tabWidth", 4);
        generalConfig.put("useTabs", false);
        generalConfig.put("lineLength", 100);

        Map<String, Object> springConfig = new HashMap<>();
        springConfig.put("maxMethodLines", 50);
        springConfig.put("maxMethodComplexity", 15);

        Map<String, Object> reactConfig = new HashMap<>();
        reactConfig.put("maxComponentLines", 150);
        reactConfig.put("enforceHookDependencies", true);

        Map<String, Map<String, Object>> pluginConfigs = new HashMap<>();
        pluginConfigs.put("spring", springConfig);
        pluginConfigs.put("react", reactConfig);

        return new FormatterConfig(generalConfig, pluginConfigs);
    }

    private static AdvancedCodeFormatter createFormatter(FormatterConfig config, boolean verbose) {
        AdvancedCodeFormatter formatter = new AdvancedCodeFormatter(config);

        try {
            formatter.registerPlugin(FileType.JAVA, new SpringBootFormatter());
            formatter.registerPlugin(FileType.JAVASCRIPT, new ReactJSFormatter());
            formatter.registerPlugin(FileType.JSX, new ReactJSFormatter());
            formatter.registerPlugin(FileType.TYPESCRIPT, new ReactJSFormatter());
            formatter.registerPlugin(FileType.TSX, new ReactJSFormatter());
        } catch (Exception e) {
            System.err.println("Warning: Failed to initialize one or more formatter plugins: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
        }

        return formatter;
    }

    private static List<Path> findFiles(Path path, List<String> ignorePatterns) throws IOException {
        if (Files.isRegularFile(path)) {
            return List.of(path);
        }

        try (Stream<Path> walk = Files.walk(path)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> isSupported(p))
                    .filter(p -> !isIgnored(p, path, ignorePatterns))
                    .collect(Collectors.toList());
        }
    }

    private static boolean isSupported(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        return fileName.endsWith(".java") ||
                fileName.endsWith(".js") ||
                fileName.endsWith(".jsx") ||
                fileName.endsWith(".ts") ||
                fileName.endsWith(".tsx");
    }

    private static boolean isIgnored(Path file, Path basePath, List<String> ignorePatterns) {
        if (ignorePatterns == null || ignorePatterns.isEmpty()) {
            return false;
        }

        String relativePath = basePath.relativize(file).toString().replace("\\", "/");

        for (String pattern : ignorePatterns) {
            // Basic glob pattern matching - a real implementation would use something more robust
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
            } else if (pattern.equals(relativePath)) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasOption(String[] args, String option) {
        for (String arg : args) {
            if (arg.equals(option)) {
                return true;
            }
        }
        return false;
    }

    private static String getOptionValue(String[] args, String option) {
        String prefix = option + "=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }
}