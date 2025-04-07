package com.codeformatter.cli;

import com.codeformatter.api.FormatterResult;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.core.AdvancedCodeFormatter;
import com.codeformatter.plugins.FileType;
import com.codeformatter.plugins.react.ReactJSFormatter;
import com.codeformatter.plugins.spring.SpringBootFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];

        try {
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

    private static void printUsage() {
        System.out.println("Advanced Code Formatter CLI");
        System.out.println("Usage:");
        System.out.println("  codeformatter init                 - Initialize configuration file");
        System.out.println("  codeformatter format <path>        - Format files in path");
        System.out.println("  codeformatter check <path>         - Check files without formatting");
        System.out.println("  codeformatter --help               - Show this help");
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

        FormatterConfig config = loadConfig();
        AdvancedCodeFormatter formatter = createFormatter(config);

        AtomicInteger fileCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        List<Path> filesToFormat = findFiles(path);
        System.out.println("Found " + filesToFormat.size() + " files to format");

        for (Path file : filesToFormat) {
            try {
                String source = Files.readString(file);
                FormatterResult result = formatter.formatFile(file, source);

                if (result.isSuccessful()) {
                    Files.writeString(file, result.getFormattedCode());
                    System.out.println("Formatted: " + file);

                    if (!result.getAppliedRefactorings().isEmpty()) {
                        System.out.println("  Applied refactorings:");
                        result.getAppliedRefactorings().forEach(r ->
                                System.out.println("    - " + r.getDescription()));
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
                errorCount.incrementAndGet();
            }
        }

        System.out.println("\nFormatting complete:");
        System.out.println("  Processed files: " + fileCount.get());
        System.out.println("  Files with errors: " + errorCount.get());
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

        FormatterConfig config = loadConfig();
        AdvancedCodeFormatter formatter = createFormatter(config);

        AtomicInteger fileCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        List<Path> filesToCheck = findFiles(path);
        System.out.println("Found " + filesToCheck.size() + " files to check");

        for (Path file : filesToCheck) {
            try {
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
                }

                fileCount.incrementAndGet();
            } catch (Exception e) {
                System.err.println("Error checking file: " + file);
                System.err.println("  " + e.getMessage());
                errorCount.incrementAndGet();
            }
        }

        System.out.println("\nCheck complete:");
        System.out.println("  Checked files: " + fileCount.get());
        System.out.println("  Files needing formatting: " + errorCount.get());

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

        String defaultConfig = """
            # Advanced Code Formatter Configuration
            
            general:
              indentSize: 4
              tabWidth: 4
              useTabs: false
              lineLength: 100
              ignoreFiles:
                - "**/*.min.js"
                - "**/node_modules/**"
                - "**/build/**"
                - "**/dist/**"
                - "**/target/**"
            
            plugins:
              spring:
                maxMethodLines: 50
                maxMethodComplexity: 15
                enforceDesignPatterns: true
                enforceDependencyInjection: constructor
                importOrganization:
                  groups:
                    - static
                    - java
                    - javax
                    - org.springframework
                    - com
                    - org
            
              react:
                maxComponentLines: 150
                enforceHookDependencies: true
                extractComponents: true
                jsxLineBreakRule: multiline
                importOrganization:
                  groups:
                    - react
                    - external
                    - internal
                    - css
            """;

        Files.writeString(configPath, defaultConfig);
        System.out.println("Created configuration file: " + CONFIG_FILE_NAME);
    }

    private static FormatterConfig loadConfig() throws IOException {
        Path configPath = Paths.get(CONFIG_FILE_NAME);

        if (!Files.exists(configPath)) {
            System.out.println("Configuration file not found: " + CONFIG_FILE_NAME);
            System.out.println("Using default configuration");
            return createDefaultConfig();
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> config = mapper.readValue(configPath.toFile(), Map.class);

        Map<String, Object> generalConfig = (Map<String, Object>) config.getOrDefault("general", new HashMap<>());
        Map<String, Map<String, Object>> pluginConfigs = (Map<String, Map<String, Object>>)
                config.getOrDefault("plugins", new HashMap<>());

        return new FormatterConfig(generalConfig, pluginConfigs);
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

    private static AdvancedCodeFormatter createFormatter(FormatterConfig config) {
        AdvancedCodeFormatter formatter = new AdvancedCodeFormatter(config);

        formatter.registerPlugin(FileType.JAVA, new SpringBootFormatter());
        formatter.registerPlugin(FileType.JAVASCRIPT, new ReactJSFormatter());
        formatter.registerPlugin(FileType.JSX, new ReactJSFormatter());
        formatter.registerPlugin(FileType.TYPESCRIPT, new ReactJSFormatter());
        formatter.registerPlugin(FileType.TSX, new ReactJSFormatter());

        return formatter;
    }

    private static List<Path> findFiles(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return List.of(path);
        }

        try (Stream<Path> walk = Files.walk(path)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(FormatterCli::isSupported)
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
}