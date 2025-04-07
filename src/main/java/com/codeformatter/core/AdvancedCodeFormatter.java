package com.codeformatter.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
public class AdvancedCodeFormatter implements CodeFormatter {
    
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
        FileType fileType = detectFileType(filePath);
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
        // Implementation for handling multiple files
        // Could include parallel processing
        return null; // Not implemented in this example
    }
    
    /**
     * Detects the file type based on file extension.
     */
    private FileType detectFileType(Path filePath) {
        String fileName = filePath.getFileName().toString();
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
        
        switch (extension.toLowerCase()) {
            case "java":
                return FileType.JAVA;
            case "js":
                return FileType.JAVASCRIPT;
            case "jsx":
                return FileType.JSX;
            case "ts":
                return FileType.TYPESCRIPT;
            case "tsx":
                return FileType.TSX;
            default:
                return FileType.UNKNOWN;
        }
    }
}