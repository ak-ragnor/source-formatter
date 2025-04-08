package com.codeformatter.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.codeformatter.api.CodeFormatter;
import com.codeformatter.api.FormatterPlugin;
import com.codeformatter.api.FormatterResult;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.FileType;

/**
 * Core implementation of the Advanced Source Code Formatter.
 * This class orchestrates the formatting process, delegating to appropriate
 * language-specific plugins based on file type.
 */
public class AdvancedCodeFormatter implements CodeFormatter, AutoCloseable {

    private final Map<FileType, FormatterPlugin> plugins = new ConcurrentHashMap<>();
    private final FormatterConfig config;

    public AdvancedCodeFormatter(FormatterConfig config) {
        this.config = config;
    }

    /**
     * Registers a plugin for a specific file type.
     */
    public void registerPlugin(FileType fileType, FormatterPlugin plugin) {
        plugins.put(fileType, plugin);
        plugin.initialize(config);
    }

    /**
     * Formats a single file using the appropriate plugin.
     */
    @Override
    public FormatterResult formatFile(Path filePath, String sourceCode) {
        FileType fileType = FileType.detect(filePath);
        FormatterPlugin plugin = plugins.get(fileType);

        if (plugin == null) {
            return FormatterResult.builder()
                    .successful(false)
                    .formattedCode(sourceCode)
                    .addError(new FormatterError(
                            Severity.ERROR,
                            "No plugin registered for file type: " + fileType,
                            1, 1))
                    .build();
        }

        return plugin.format(filePath, sourceCode);
    }

    /**
     * Formats a directory of files.
     */
    @Override
    public Map<Path, FormatterResult> formatDirectory(Path directory) {
        try {
            return Files.walk(directory)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        FileType type = FileType.detect(path);
                        return type != FileType.UNKNOWN && plugins.containsKey(type);
                    })
                    .parallel()
                    .collect(Collectors.toConcurrentMap(
                            path -> path,
                            path -> {
                                try {
                                    String content = Files.readString(path, StandardCharsets.UTF_8);
                                    return formatFile(path, content);
                                } catch (IOException e) {
                                    // Handle IO errors
                                    return FormatterResult.builder()
                                            .successful(false)
                                            .formattedCode(null)
                                            .addError(new FormatterError(
                                                    Severity.FATAL,
                                                    "Failed to read file: " + e.getMessage(),
                                                    1, 1))
                                            .build();
                                } catch (Exception e) {
                                    return FormatterResult.builder()
                                            .successful(false)
                                            .formattedCode(null)
                                            .addError(new FormatterError(
                                                    Severity.FATAL,
                                                    "Unexpected error: " + e.getMessage(),
                                                    1, 1))
                                            .build();
                                }
                            }
                    ));
        } catch (IOException e) {
            return Map.of();
        }
    }

    /**
     * Closes all plugins and releases resources.
     */
    @Override
    public void close() throws Exception {
        Exception firstException = null;

        // Close all plugins that implement AutoCloseable
        for (FormatterPlugin plugin : plugins.values()) {
            if (plugin instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) plugin).close();
                } catch (Exception e) {
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