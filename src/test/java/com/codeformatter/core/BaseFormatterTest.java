package com.codeformatter.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.codeformatter.api.FormatterResult;
import com.codeformatter.config.ConfigurationLoader;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.FileType;
import com.codeformatter.plugins.spring.SpringBootFormatter;
import com.codeformatter.plugins.react.ReactJSFormatter;

/**
 * Base test class that provides common setup and utilities for formatting tests.
 */
public abstract class BaseFormatterTest {

    protected AdvancedCodeFormatter formatter;
    protected FormatterConfig config;

    @TempDir
    protected Path tempDir;

    @BeforeEach
    public void setup() {
        config = ConfigurationLoader.loadDefaultConfig();

        formatter = new AdvancedCodeFormatter(config);

        formatter.registerPlugin(FileType.JAVA, new SpringBootFormatter());
        formatter.registerPlugin(FileType.JAVASCRIPT, new ReactJSFormatter());
        formatter.registerPlugin(FileType.JSX, new ReactJSFormatter());
        formatter.registerPlugin(FileType.TYPESCRIPT, new ReactJSFormatter());
        formatter.registerPlugin(FileType.TSX, new ReactJSFormatter());
    }

    @AfterEach
    public void cleanup() throws Exception {
        if (formatter != null) {
            formatter.close();
        }
    }

    /**
     * Creates a test file with the specified content in the temp directory.
     */
    protected Path createTestFile(String filename, String content) throws IOException {
        Path filePath = tempDir.resolve(filename);
        Files.writeString(filePath, content);
        return filePath;
    }

    /**
     * Asserts that a formatting result was successful and made changes.
     */
    protected void assertSuccessfulFormatting(FormatterResult result, String originalCode) {
        assertNotNull(result, "Formatting result should not be null");
        assertTrue(result.isSuccessful(), "Formatting should be successful");
        assertNotNull(result.getFormattedCode(), "Formatted code should not be null");
        assertNotEquals(originalCode, result.getFormattedCode(), "Formatting should change the code");
    }

    /**
     * Asserts that a formatting result was successful but made no changes.
     */
    protected void assertNoChangesNeeded(FormatterResult result, String originalCode) {
        assertNotNull(result, "Formatting result should not be null");
        assertTrue(result.isSuccessful(), "Formatting should be successful");
        assertNotNull(result.getFormattedCode(), "Formatted code should not be null");
        assertEquals(originalCode, result.getFormattedCode(), "Formatting should not change already formatted code");
    }

    /**
     * Asserts that a formatting result contains specific errors.
     */
    protected void assertContainsError(FormatterResult result, String errorMessageSubstring) {
        assertNotNull(result, "Formatting result should not be null");
        assertFalse(result.getErrors().isEmpty(), "Result should contain errors");

        boolean found = result.getErrors().stream()
                .anyMatch(error -> error.getMessage().contains(errorMessageSubstring));

        assertTrue(found, "Result should contain error with message: " + errorMessageSubstring);
    }
}