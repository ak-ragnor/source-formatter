package com.codeformatter.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.codeformatter.api.CodeFormatter;
import com.codeformatter.api.FormatterPlugin;
import com.codeformatter.api.FormatterResult;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.FileType;
import com.codeformatter.util.LoggerUtil;

/**
 * Thread-safe implementation of the Advanced Source Code Formatter.
 * This class orchestrates the formatting process, delegating to appropriate
 * language-specific plugins based on file type.
 */
public class AdvancedCodeFormatter implements CodeFormatter, AutoCloseable {
    private static final Logger logger = LoggerUtil.getLogger(AdvancedCodeFormatter.class);

    private final Map<FileType, FormatterPlugin> plugins = new ConcurrentHashMap<>();
    private final FormatterConfig config;

    private final AtomicInteger processedFileCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);

    /**
     * Creates a new formatter with the provided configuration.
     */
    public AdvancedCodeFormatter(FormatterConfig config) {
        this.config = config;
        logger.info("Advanced Code Formatter initialized with configuration");
    }

    /**
     * Registers a plugin for a specific file type.
     */
    public void registerPlugin(FileType fileType, FormatterPlugin plugin) {
        plugins.put(fileType, plugin);
        plugin.initialize(config);
        logger.info("Registered plugin for file type: " + fileType.getDescription());
    }

    /**
     * Formats a single file using the appropriate plugin.
     */
    @Override
    public FormatterResult formatFile(Path filePath, String sourceCode) {
        FileType fileType = FileType.detect(filePath);
        FormatterPlugin plugin = plugins.get(fileType);

        if (plugin == null) {
            logger.warning("No plugin found for file type: " + fileType + " - " + filePath);
            return FormatterResult.builder()
                    .successful(false)
                    .formattedCode(sourceCode)
                    .addError(new FormatterError(
                            Severity.ERROR,
                            "No plugin registered for file type: " + fileType,
                            1, 1))
                    .build();
        }

        try {
            processedFileCount.incrementAndGet();
            FormatterResult result = plugin.format(filePath, sourceCode);

            if (result.isSuccessful()) {
                successCount.incrementAndGet();
                logger.fine("Successfully formatted: " + filePath);
            } else {
                errorCount.incrementAndGet();
                logger.warning("Failed to format: " + filePath + " - " +
                        result.getErrors().stream()
                                .map(e -> e.getSeverity() + ": " + e.getMessage())
                                .collect(Collectors.joining(", ")));
            }

            return result;
        } catch (Exception e) {
            errorCount.incrementAndGet();
            logger.log(Level.SEVERE, "Unexpected error formatting file: " + filePath, e);

            return FormatterResult.builder()
                    .successful(false)
                    .formattedCode(sourceCode)
                    .addError(new FormatterError(
                            Severity.FATAL,
                            "Unexpected error: " + e.getMessage(),
                            1, 1))
                    .build();
        }
    }

    /**
     * Formats a directory of files using a thread pool.
     * This implementation is thread-safe and handles parallelism properly.
     */
    @Override
    public Map<Path, FormatterResult> formatDirectory(Path directory) {
        return formatDirectory(directory, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Formats a directory with a specified thread count.
     */
    public Map<Path, FormatterResult> formatDirectory(Path directory, int threadCount) {
        ConcurrentHashMap<Path, FormatterResult> results = new ConcurrentHashMap<>();

        try {
            if (!Files.exists(directory)) {
                logger.warning("Directory does not exist: " + directory);
                return results;
            }

            if (!Files.isDirectory(directory)) {
                logger.warning("Path is not a directory: " + directory);
                return results;
            }

            // Find all files to process
            List<Path> filesToProcess = Files.walk(directory)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        FileType type = FileType.detect(path);
                        return type != FileType.UNKNOWN && plugins.containsKey(type);
                    })
                    .toList();

            logger.info("Found " + filesToProcess.size() + " files to process in " + directory);

            if (filesToProcess.isEmpty()) {
                return results;
            }

            // Use a thread pool to process files in parallel
            try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {

                for (Path file : filesToProcess) {
                    executor.submit(() -> {
                        try {
                            String content = Files.readString(file, StandardCharsets.UTF_8);
                            FormatterResult result = formatFile(file, content);
                            results.put(file, result);
                        } catch (IOException e) {
                            results.put(file, FormatterResult.builder()
                                    .successful(false)
                                    .formattedCode(null)
                                    .addError(new FormatterError(
                                            Severity.FATAL,
                                            "Failed to read file: " + e.getMessage(),
                                            1, 1))
                                    .build());
                        } catch (Exception e) {
                            results.put(file, FormatterResult.builder()
                                    .successful(false)
                                    .formattedCode(null)
                                    .addError(new FormatterError(
                                            Severity.FATAL,
                                            "Unexpected error: " + e.getMessage(),
                                            1, 1))
                                    .build());
                        }
                    });
                }

                // Shutdown executor and wait for all tasks to complete
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(30, TimeUnit.MINUTES)) {
                        logger.warning("Timeout waiting for file processing to complete");
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    logger.log(Level.WARNING, "Processing interrupted", e);
                    Thread.currentThread().interrupt();
                    executor.shutdownNow();
                }
            }

            logger.info("Processed " + results.size() + " files");

            return results;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error scanning directory: " + directory, e);
            return results;
        }
    }

    /**
     * Gets the number of files processed.
     */
    public int getProcessedFileCount() {
        return processedFileCount.get();
    }

    /**
     * Gets the number of successfully formatted files.
     */
    public int getSuccessCount() {
        return successCount.get();
    }

    /**
     * Gets the number of files with formatting errors.
     */
    public int getErrorCount() {
        return errorCount.get();
    }

    /**
     * Checks if a plugin is registered for the given file type.
     */
    public boolean hasPluginFor(FileType fileType) {
        return plugins.containsKey(fileType);
    }

    /**
     * Gets the number of registered plugins.
     */
    public int getPluginCount() {
        return plugins.size();
    }

    /**
     * Closes all plugins and releases resources.
     */
    @Override
    public void close() throws Exception {
        logger.info("Closing formatter: processed=" + processedFileCount.get() +
                ", success=" + successCount.get() + ", errors=" + errorCount.get());

        Exception firstException = null;

        // Close all plugins that implement AutoCloseable
        for (Map.Entry<FileType, FormatterPlugin> entry : plugins.entrySet()) {
            FormatterPlugin plugin = entry.getValue();
            if (plugin instanceof AutoCloseable) {
                try {
                    logger.fine("Closing plugin for file type: " + entry.getKey());
                    ((AutoCloseable) plugin).close();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error closing plugin for file type: " + entry.getKey(), e);
                    // Keep track of the first exception but continue closing other plugins
                    if (firstException == null) {
                        firstException = e;
                    }
                }
            }
        }

        plugins.clear();

        if (firstException != null) {
            throw firstException;
        }
    }
}