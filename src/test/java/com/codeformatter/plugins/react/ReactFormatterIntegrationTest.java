package com.codeformatter.plugins.react;

import static org.junit.jupiter.api.Assertions.*;

import com.codeformatter.api.FormatterResult;
import com.codeformatter.api.Refactoring;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.core.AdvancedCodeFormatter;
import com.codeformatter.plugins.FileType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Integration tests for React formatting within the complete code formatter pipeline. */
@Tag("integration")
public class ReactFormatterIntegrationTest {

  private AdvancedCodeFormatter formatter;
  private FormatterConfig config;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    // Create test configuration
    Map<String, Object> generalConfig = new HashMap<>();
    generalConfig.put("indentSize", 2);
    generalConfig.put("lineLength", 80);
    generalConfig.put("useTabs", false);

    Map<String, Object> reactConfig = new HashMap<>();
    reactConfig.put("maxComponentLines", 100);
    reactConfig.put("enforceHookDependencies", true);
    reactConfig.put("formatterImplementation", "NODEJS"); // Force Node.js implementation

    Map<String, Map<String, Object>> pluginConfigs = new HashMap<>();
    pluginConfigs.put("react", reactConfig);

    config = new FormatterConfig(generalConfig, pluginConfigs);

    // Initialize the main formatter
    formatter = new AdvancedCodeFormatter(config);

    // Register our React formatter
    formatter.registerPlugin(FileType.JAVASCRIPT, ReactFormatterFactory.createFormatter(config));
    formatter.registerPlugin(FileType.JSX, ReactFormatterFactory.createFormatter(config));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (formatter != null) {
      formatter.close();
    }
  }

  @Test
  @DisplayName("Test React formatter within complete pipeline")
  void testCompleteFormattingPipeline() throws IOException {
    // Create a sample React file with various issues
    String reactCode =
        "import './styles.css';\n"
            + "import React from 'react';\n"
            + "import { useState, useEffect } from 'react';\n\n"
            + "function   MessyComponent( ) {\n"
            + "    const [count, setCount] = useState(0);\n"
            + "   \n"
            + "    // Effect with missing dependency\n"
            + "    useEffect(() => {\n"
            + "        document.title = `Count: ${count}`;\n"
            + "    }, []);\n"
            + "   \n"
            + "    return (\n"
            + "        <div    className=\"container\"   >\n"
            + "            <h1>Messy Component</h1>\n"
            + "            <p>Count: {count}</p>\n"
            + "            <button    onClick={() => setCount(count + 1)}   >Increment</button>\n"
            + "        </div>\n"
            + "    );\n"
            + "}";

    Path reactFile = tempDir.resolve("MessyComponent.jsx");
    Files.writeString(reactFile, reactCode);

    // Format using the complete formatter pipeline
    FormatterResult result = formatter.formatFile(reactFile, reactCode);

    // Verify formatting
    assertTrue(result.isSuccessful(), "Formatting should be successful");
    assertNotNull(result.getFormattedCode(), "Formatted code should not be null");
    assertNotEquals(reactCode, result.getFormattedCode(), "Code should be changed");

    // Check that import order was fixed
    String formattedCode = result.getFormattedCode();
    int reactImportPos = formattedCode.indexOf("import React");
    int stylesImportPos = formattedCode.indexOf("import './styles.css'");
    assertTrue(reactImportPos < stylesImportPos, "React imports should come before styles");

    // Check that formatting was improved
    assertFalse(
        formattedCode.contains("function   MessyComponent"), "Extra spaces should be removed");
    assertFalse(formattedCode.contains("<div    className"), "Extra spaces should be removed");

    // Check for linting warnings
    assertFalse(result.getErrors().isEmpty(), "Should detect React issues");

    // Verify refactorings were applied
    assertFalse(result.getAppliedRefactorings().isEmpty(), "Should apply refactorings");

    boolean hasFormattingRefactoring = false;
    for (Refactoring refactoring : result.getAppliedRefactorings()) {
      if (refactoring.getType().equals("FORMATTING")) {
        hasFormattingRefactoring = true;
        break;
      }
    }

    assertTrue(hasFormattingRefactoring, "Should include formatting refactoring");
  }

  @Test
  @DisplayName("Test directory processing with React files")
  void testDirectoryProcessing() throws IOException {
    // Create multiple React/JS files
    createSampleFile("Component1.jsx", "function Component1() { return <div>Test</div>; }");
    createSampleFile(
        "Component2.jsx", "function   Component2(  ) {return(<div><h1>Test</h1></div>);}");
    createSampleFile("utils.js", "function formatDate(date){return date.toISOString()}");

    // Format the entire directory
    Map<Path, FormatterResult> results = formatter.formatDirectory(tempDir);

    // Verify results
    assertEquals(3, results.size(), "Should format all three files");

    // Check that each file was processed
    for (Map.Entry<Path, FormatterResult> entry : results.entrySet()) {
      Path file = entry.getKey();
      FormatterResult result = entry.getValue();

      assertTrue(result.isSuccessful(), "Formatting " + file + " should be successful");
      assertNotNull(result.getFormattedCode(), "Formatted code should not be null for " + file);
    }
  }

  @Test
  @DisplayName("Test React with hook dependencies")
  void testReactHookDependencies() throws IOException {
    // Create a React file with hook dependency issues
    String reactCode =
        "import React, { useState, useEffect, useCallback } from 'react';\n\n"
            + "function HooksComponent() {\n"
            + "  const [count, setCount] = useState(0);\n"
            + "  const [name, setName] = useState('John');\n\n"
            + "  // Missing dependencies\n"
            + "  useEffect(() => {\n"
            + "    document.title = `${name}: ${count} clicks`;\n"
            + "  }, []);\n\n"
            + "  // Missing dependency\n"
            + "  const handleClick = useCallback(() => {\n"
            + "    setCount(count + 1);\n"
            + "  }, []);\n\n"
            + "  return (\n"
            + "    <div>\n"
            + "      <p>{name} clicked {count} times</p>\n"
            + "      <button onClick={handleClick}>Click me</button>\n"
            + "    </div>\n"
            + "  );\n"
            + "}";

    Path reactFile = tempDir.resolve("HooksComponent.jsx");
    Files.writeString(reactFile, reactCode);

    // Format the file
    FormatterResult result = formatter.formatFile(reactFile, reactCode);

    // Check hook dependency warnings
    assertFalse(result.getErrors().isEmpty(), "Should detect hook dependency issues");

    boolean hasHookWarning =
        result.getErrors().stream()
            .anyMatch(
                error ->
                    error.getMessage().contains("exhaustive-deps")
                        || error.getMessage().contains("dependency")
                        || error.getSuggestion() != null
                            && error.getSuggestion().contains("dependencies"));

    assertTrue(hasHookWarning, "Should include hook dependency warnings");
  }

  /** Helper method to create a sample file. */
  private Path createSampleFile(String filename, String content) throws IOException {
    Path file = tempDir.resolve(filename);
    Files.writeString(file, content);
    return file;
  }
}
