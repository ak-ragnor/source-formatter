package com.codeformatter.plugins.react;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.codeformatter.api.FormatterResult;
import com.codeformatter.config.FormatterConfig;

/**
 * Tests for ReactJSFormatter functionality.
 */
public class ReactJSFormatterTest {

    private ReactJSFormatter formatter;
    private FormatterConfig config;

    @TempDir
    private Path tempDir;

    @BeforeEach
    public void setup() {
        // Create config with test settings
        Map<String, Object> generalConfig = new HashMap<>();
        generalConfig.put("indentSize", 2); // Typical for JS
        generalConfig.put("lineLength", 80); // Stricter for testing

        Map<String, Object> reactConfig = new HashMap<>();
        reactConfig.put("maxComponentLines", 50); // Stricter for testing
        reactConfig.put("enforceHookDependencies", true);
        reactConfig.put("extractComponents", true);
        reactConfig.put("jsxLineBreakRule", "multiline");

        Map<String, Map<String, Object>> pluginConfigs = new HashMap<>();
        pluginConfigs.put("react", reactConfig);

        config = new FormatterConfig(generalConfig, pluginConfigs);

        formatter = new ReactJSFormatter();
        formatter.initialize(config);
    }

    @AfterEach
    public void cleanup() throws Exception {
        if (formatter != null) {
            formatter.close();
        }
    }

    @Test
    @DisplayName("Should detect React hook dependency issues")
    public void testHookDependencyDetection() {
        // Create React code with hook dependency issues
        String reactCode = "import React, { useState, useEffect } from 'react';\n\n" +
                "function DependencyIssue() {\n" +
                "  const [count, setCount] = useState(0);\n" +
                "  const [name, setName] = useState('John');\n\n" +
                "  // Missing dependency: count\n" +
                "  useEffect(() => {\n" +
                "    document.title = `${name}: ${count} clicks`;\n" +
                "  }, []);\n\n" + // Empty dependency array but uses count
                "  return (\n" +
                "    <div>\n" +
                "      <p>{name} clicked {count} times</p>\n" +
                "      <button onClick={() => setCount(count + 1)}>Click me</button>\n" +
                "    </div>\n" +
                "  );\n" +
                "}";

        Path reactFile = tempDir.resolve("DependencyIssue.jsx");

        // Format and check
        FormatterResult result = formatter.format(reactFile, reactCode);

        // Should detect hook dependency issues
        assertFalse(result.getErrors().isEmpty(), "Should detect errors");
        assertTrue(result.getErrors().stream()
                        .anyMatch(error -> error.getMessage().contains("empty dependency array")),
                "Should detect hook dependency issues");
    }

    @Test
    @DisplayName("Should detect and fix React hook dependencies")
    public void testHookDependencyFix() {
        // Create React code with hook dependency issues
        String reactCode = "import React, { useState, useEffect, useCallback } from 'react';\n\n" +
                "function DependencyFix() {\n" +
                "  const [count, setCount] = useState(0);\n" +
                "  const [name, setName] = useState('John');\n\n" +
                "  // Missing dependencies\n" +
                "  useEffect(() => {\n" +
                "    document.title = `${name}: ${count} clicks`;\n" +
                "  }, []);\n\n" + // Should include name and count
                "  // Missing dependency\n" +
                "  const handleClick = useCallback(() => {\n" +
                "    setCount(count + 1);\n" +
                "  }, []);\n\n" + // Should include count
                "  return (\n" +
                "    <div>\n" +
                "      <p>{name} clicked {count} times</p>\n" +
                "      <button onClick={handleClick}>Click me</button>\n" +
                "    </div>\n" +
                "  );\n" +
                "}";

        Path reactFile = tempDir.resolve("DependencyFix.jsx");

        // Format and check
        FormatterResult result = formatter.format(reactFile, reactCode);

        // Should detect and fix hook dependencies
        assertTrue(result.isSuccessful(), "Formatting should be successful");

        // Check for refactoring in the result
        boolean hasHookDependencyFix = result.getAppliedRefactorings().stream()
                .anyMatch(r -> r.getType().equals("HOOK_DEPENDENCIES_FIX"));

        assertTrue(hasHookDependencyFix, "Should apply hook dependency fix refactoring");

        // Verify the dependencies were added
        String formattedCode = result.getFormattedCode();
        assertTrue(formattedCode.contains("[name, count]") ||
                        formattedCode.contains("[count, name]"),
                "Effect should have name and count dependencies");
        assertTrue(formattedCode.contains("useCallback(() => {") &&
                        formattedCode.contains("[count]"),
                "Callback should have count dependency");
    }

    @Test
    @DisplayName("Should detect component size issues")
    public void testComponentSizeDetection() {
        // Create a large React component
        StringBuilder codeBuilder = new StringBuilder();
        codeBuilder.append("import React from 'react';\n\n");
        codeBuilder.append("function LargeComponent() {\n");

        // Add many JSX elements to make it large
        codeBuilder.append("  return (\n");
        codeBuilder.append("    <div className=\"container\">\n");
        codeBuilder.append("      <h1>Large Component</h1>\n");

        // Generate a lot of content to exceed the max size
        for (int i = 0; i < 40; i++) {
            codeBuilder.append("      <div className=\"item\">\n");
            codeBuilder.append("        <h2>Item ").append(i + 1).append("</h2>\n");
            codeBuilder.append("        <p>This is item number ").append(i + 1).append("</p>\n");
            codeBuilder.append("        <button>Action ").append(i + 1).append("</button>\n");
            codeBuilder.append("      </div>\n");
        }

        codeBuilder.append("    </div>\n");
        codeBuilder.append("  );\n");
        codeBuilder.append("}\n");

        String reactCode = codeBuilder.toString();
        Path reactFile = tempDir.resolve("LargeComponent.jsx");

        // Format and check
        FormatterResult result = formatter.format(reactFile, reactCode);

        // Should detect component size issues
        assertFalse(result.getErrors().isEmpty(), "Should detect errors");
        assertTrue(result.getErrors().stream()
                        .anyMatch(error -> error.getMessage().contains("exceeds recommended size")),
                "Should detect component size issues");
    }

    @Test
    @DisplayName("Should detect and organize import statements")
    public void testImportOrganization() {
        // Create React code with unorganized imports
        String reactCode = "import axios from 'axios';\n" + // External library
                "import './App.css';\n" + // CSS import
                "import React from 'react';\n" + // React should be first
                "import { useEffect } from 'react';\n" + // React-related import
                "import Component from './Component';\n" + // Internal import
                "import { useState } from 'react';\n"; // Another React import

        Path reactFile = tempDir.resolve("ImportIssue.jsx");

        // Format and check
        FormatterResult result = formatter.format(reactFile, reactCode);

        // Should detect and fix import organization
        assertTrue(result.isSuccessful(), "Formatting should be successful");

        // Check for refactoring in the result
        boolean hasImportOrganization = result.getAppliedRefactorings().stream()
                .anyMatch(r -> r.getType().equals("IMPORT_ORGANIZATION"));

        assertTrue(hasImportOrganization, "Should apply import organization refactoring");

        // Verify the imports were organized correctly
        String formattedCode = result.getFormattedCode();

        // React imports should be first
        int reactImportPos = formattedCode.indexOf("import React");
        int cssImportPos = formattedCode.indexOf("import './App.css'");
        int axiosImportPos = formattedCode.indexOf("import axios");
        int componentImportPos = formattedCode.indexOf("import Component");

        // Verify order: React -> External (axios) -> Internal (Component) -> CSS
        assertTrue(reactImportPos < axiosImportPos, "React imports should come first");
        assertTrue(axiosImportPos < componentImportPos, "External imports should come before internal");
        assertTrue(componentImportPos < cssImportPos, "CSS imports should come last");
    }

    @Test
    @DisplayName("Should detect JSX style issues")
    public void testJsxStyleDetection() {
        // Create React code with JSX style issues
        String reactCode = "import React from 'react';\n\n" +
                "function StyleIssue() {\n" +
                "  return (\n" +
                "    <div>\n" +
                "      {/* Inline style instead of className */}\n" +
                "      <div style={{ color: 'red', padding: '20px', margin: '10px', backgroundColor: 'blue', fontSize: '16px', fontWeight: 'bold' }}>\n" +
                "        <h1>Heading</h1>\n" +
                "        <p>This component has styling issues</p>\n" +
                "      </div>\n" +
                "      {/* Too many attributes on one line */}\n" +
                "      <button className=\"btn\" onClick={() => alert('clicked')} disabled={false} id=\"main-button\" data-test=\"test-button\" aria-label=\"Click me\" title=\"Main action\">\n" +
                "        Click me\n" +
                "      </button>\n" +
                "    </div>\n" +
                "  );\n" +
                "}";

        Path reactFile = tempDir.resolve("StyleIssue.jsx");

        // Format and check
        FormatterResult result = formatter.format(reactFile, reactCode);

        // Should detect JSX style issues
        assertFalse(result.getErrors().isEmpty(), "Should detect errors");
        assertTrue(result.getErrors().stream()
                        .anyMatch(error -> error.getMessage().contains("Inline styles detected") ||
                                error.getMessage().contains("JSX element with many attributes")),
                "Should detect JSX style issues");

        // Check for style improvement refactoring
        boolean hasStyleImprovement = result.getAppliedRefactorings().stream()
                .anyMatch(r -> r.getType().equals("JSX_STYLE_IMPROVEMENT"));

        assertTrue(hasStyleImprovement, "Should apply JSX style improvement refactoring");
    }

    @Test
    @DisplayName("Should handle invalid React code gracefully")
    public void testInvalidReactCode() {
        // Create invalid React code with syntax errors
        String invalidCode = "import React from 'react';\n\n" +
                "function BrokenComponent() {\n" +
                "  return (\n" +
                "    <div>\n" +
                "      <h1>This is broken</h1\n" + // Missing closing bracket
                "      <p>This component has syntax errors</p>\n" +
                "    </div\n" + // Missing closing bracket
                "  );\n" +
                "}";

        Path reactFile = tempDir.resolve("BrokenComponent.jsx");

        // Format and check
        FormatterResult result = formatter.format(reactFile, invalidCode);

        // Should not crash, but report error
        assertNotNull(result, "Should return a result even for invalid code");
        assertFalse(result.isSuccessful(), "Formatting invalid code should not be successful");
        assertTrue(result.getErrors().stream()
                        .anyMatch(error -> error.getMessage().contains("Failed to parse")),
                "Should report parsing error");
    }

    @Test
    @DisplayName("Should detect issues with React state management")
    public void testStateManagementDetection() {
        // Create React code with state management issues
        String reactCode = "import React, { useState } from 'react';\n\n" +
                "function TooManyStates() {\n" +
                "  // Too many useState hooks\n" +
                "  const [name, setName] = useState('');\n" +
                "  const [age, setAge] = useState(0);\n" +
                "  const [email, setEmail] = useState('');\n" +
                "  const [phone, setPhone] = useState('');\n" +
                "  const [address, setAddress] = useState('');\n" +
                "  const [city, setCity] = useState('');\n" + // 6th useState - excessive
                "  \n" +
                "  // Object state without proper update pattern\n" +
                "  const [user, setUser] = useState({ id: 1, permissions: ['read', 'write'] });\n" +
                "  \n" +
                "  const updatePermission = (permission) => {\n" +
                "    // Direct mutation of object state\n" +
                "    user.permissions.push(permission);\n" +
                "    setUser(user); // Should create a new object\n" +
                "  };\n" +
                "  \n" +
                "  return (\n" +
                "    <div>\n" +
                "      <h1>User Profile</h1>\n" +
                "      <p>Name: {name}</p>\n" +
                "      <p>Age: {age}</p>\n" +
                "      {/* and so on... */}\n" +
                "    </div>\n" +
                "  );\n" +
                "}";

        Path reactFile = tempDir.resolve("TooManyStates.jsx");

        // Format and check
        FormatterResult result = formatter.format(reactFile, reactCode);

        // Should detect state management issues
        assertFalse(result.getErrors().isEmpty(), "Should detect errors");
        assertTrue(result.getErrors().stream()
                        .anyMatch(error -> error.getMessage().contains("uses") &&
                                error.getMessage().contains("useState hooks")),
                "Should detect too many useState hooks");

        assertTrue(result.getErrors().stream()
                        .anyMatch(error -> error.getMessage().contains("Object/array state detected")),
                "Should detect object state that needs careful updates");
    }
}