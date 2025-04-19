package com.codeformatter.plugins.react;

import static org.junit.jupiter.api.Assertions.*;

import com.codeformatter.api.FormatterResult;
import com.codeformatter.config.FormatterConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the ReactJSFormatter class using Node.js implementation. */
@Tag("integration")
class ReactJSFormatterTest {

  private ReactJSFormatter formatter;
  private FormatterConfig config;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    // Create test configuration
    Map<String, Object> generalConfig = new HashMap<>();
    generalConfig.put("indentSize", 2);
    generalConfig.put("lineLength", 80);

    Map<String, Object> reactConfig = new HashMap<>();
    reactConfig.put("maxComponentLines", 100);
    reactConfig.put("enforceHookDependencies", true);

    Map<String, Map<String, Object>> pluginConfigs = new HashMap<>();
    pluginConfigs.put("react", reactConfig);

    config = new FormatterConfig(generalConfig, pluginConfigs);

    // Initialize formatter using the factory to ensure we're using the same
    // approach as the main application
    formatter = new ReactJSFormatter();
    formatter.initialize(config);
  }

  @AfterEach
  void tearDown() {
    if (formatter != null) {
      formatter.close();
    }
  }

  @Test
  @DisplayName("Test JavaScript formatting")
  void testJavaScriptFormatting() throws IOException {
    // Create test JavaScript file
    String jsCode = "function test(){    return 1+2;}";
    Path jsFile = tempDir.resolve("test.js");
    Files.writeString(jsFile, jsCode);

    // Format the file
    FormatterResult result = formatter.format(jsFile, jsCode);

    // Check formatting results
    assertTrue(result.isSuccessful(), "Formatting should be successful");
    assertNotNull(result.getFormattedCode(), "Formatted code should not be null");
    assertNotEquals(jsCode, result.getFormattedCode(), "Code should be changed");

    // Verify formatting improvements
    String formattedCode = result.getFormattedCode();
    assertTrue(formattedCode.contains("function test()"), "Should preserve function name");
    assertTrue(formattedCode.contains("return 1 + 2"), "Should add spaces around operators");
  }

  @Test
  @DisplayName("Test React JSX formatting")
  void testReactFormatting() throws IOException {
    // Create test React file
    String reactCode = "function Component(){    return (<div><h1>Hello</h1><p>World</p></div>);}";
    Path reactFile = tempDir.resolve("Component.jsx");
    Files.writeString(reactFile, reactCode);

    // Format the file
    FormatterResult result = formatter.format(reactFile, reactCode);

    // Check formatting results
    assertTrue(result.isSuccessful(), "Formatting should be successful");
    assertNotNull(result.getFormattedCode(), "Formatted code should not be null");
    assertNotEquals(reactCode, result.getFormattedCode(), "Code should be changed");

    // Verify formatting improvements
    String formattedCode = result.getFormattedCode();
    assertTrue(formattedCode.contains("function Component()"), "Should preserve function name");
    assertTrue(formattedCode.contains("<div>"), "Should preserve div tag");
    assertTrue(formattedCode.contains("<h1>"), "Should preserve h1 tag");

    // The formatted code should have more whitespace
    assertTrue(
        formattedCode.length() > reactCode.length(),
        "Formatted code should be longer due to added whitespace");
  }

  @Test
  @DisplayName("Test React hook dependency analysis")
  void testHookDependencyAnalysis() throws IOException {
    // Create React code with hook dependency issues
    String reactCode =
        "import React, { useState, useEffect } from 'react';\n"
            + "function Component() {\n"
            + "  const [count, setCount] = useState(0);\n"
            + "  // Missing dependency\n"
            + "  useEffect(() => {\n"
            + "    document.title = `Count: ${count}`;\n"
            + "  }, []);\n"
            + "  return <div>{count}</div>;\n"
            + "}";

    Path reactFile = tempDir.resolve("HookComponent.jsx");
    Files.writeString(reactFile, reactCode);

    // Format the file
    FormatterResult result = formatter.format(reactFile, reactCode);

    // Should detect hook dependency issues
    assertFalse(result.getErrors().isEmpty(), "Should detect errors");
    boolean hasHookDependencyIssue =
        result.getErrors().stream()
            .anyMatch(
                error ->
                    error.getMessage().contains("exhaustive-deps")
                        || error.getMessage().contains("dependency")
                        || error.getMessage().contains("react-hooks"));

    assertTrue(hasHookDependencyIssue, "Should detect React hooks dependency issue");
  }

  @Test
  @DisplayName("Test caching mechanism")
  void testCaching() throws IOException {
    // Create test file
    String jsCode = "function test(){    return 1+2;}";
    Path jsFile = tempDir.resolve("cache-test.js");
    Files.writeString(jsFile, jsCode);

    // Format the file first time
    FormatterResult result1 = formatter.format(jsFile, jsCode);
    assertTrue(result1.isSuccessful(), "First formatting should be successful");

    // Format the same file again - should use cache
    FormatterResult result2 = formatter.format(jsFile, jsCode);
    assertTrue(result2.isSuccessful(), "Second formatting should be successful");

    // Both results should be identical
    assertEquals(
        result1.getFormattedCode(),
        result2.getFormattedCode(),
        "Cached results should be identical");

    // Check cache size
    assertTrue(formatter.getCacheSize() > 0, "Cache should contain entries");

    // Clear cache and verify
    formatter.clearCache();
    assertEquals(0, formatter.getCacheSize(), "Cache should be empty after clearing");
  }

  @Test
  @DisplayName("Test empty file handling")
  void testEmptyFile() throws IOException {
    // Create empty file
    Path emptyFile = tempDir.resolve("empty.js");
    Files.writeString(emptyFile, "");

    // Format the empty file
    FormatterResult result = formatter.format(emptyFile, "");

    // Should handle this gracefully
    assertTrue(result.isSuccessful(), "Empty file formatting should succeed");
    assertEquals("", result.getFormattedCode(), "Empty file should remain empty");
    assertTrue(result.getErrors().isEmpty(), "Should not report errors for empty file");
  }

  @Test
  @DisplayName("Test invalid code handling")
  void testInvalidCode() throws IOException {
    // Create file with syntax error
    String invalidCode = "function test( {";
    Path invalidFile = tempDir.resolve("invalid.js");
    Files.writeString(invalidFile, invalidCode);

    // Format the file with invalid code
    FormatterResult result = formatter.format(invalidFile, invalidCode);

    // Should not crash
    assertNotNull(result, "Should return a result even for invalid code");
    assertNotNull(result.getFormattedCode(), "Should return code even if formatting fails");

    // Either returns the original code or report errors
    if (result.getFormattedCode().equals(invalidCode)) {
      // If it returns the original code, there should be errors
      assertFalse(result.getErrors().isEmpty(), "Should report errors for invalid code");
    }
  }

  @Test
  @DisplayName("Test already formatted code")
  void testAlreadyFormattedCode() throws IOException {
    // Create a properly formatted file
    String formattedCode = "function test() {\n" + "  return 1 + 2;\n" + "}\n";

    Path formattedFile = tempDir.resolve("formatted.js");
    Files.writeString(formattedFile, formattedCode);

    // Format the already formatted file
    FormatterResult result = formatter.format(formattedFile, formattedCode);

    // Should recognize it's already formatted
    assertTrue(result.isSuccessful(), "Formatting should be successful");
    assertEquals(
        formattedCode, result.getFormattedCode(), "Already formatted code should not change");
    assertTrue(
        result.getAppliedRefactorings().isEmpty(),
        "No refactorings needed for already formatted code");
  }
}
