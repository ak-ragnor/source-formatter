package com.codeformatter.api;

import java.nio.file.Path;

import com.codeformatter.config.FormatterConfig;

/**
 * Interface for language-specific formatter plugins.
 */
public interface FormatterPlugin {
    /**
     * Initialize the plugin with the configuration.
     */
    void initialize(FormatterConfig config);
    
    /**
     * Format the provided source code according to rules.
     */
    FormatterResult format(Path filePath, String sourceCode);
}
