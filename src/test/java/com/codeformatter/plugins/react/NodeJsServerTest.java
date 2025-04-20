package com.codeformatter.plugins.react;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for the NodeJsServer class that handles communication with the Node.js process.
 * This class focuses on essential server functionality.
 */
@Tag("integration")
class NodeJsServerTest {

  private NodeJsServer server;

  @BeforeEach
  void setUp() {
    server = new NodeJsServer();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  @DisplayName("Test server start and stop")
  void testServerStartAndStop() throws IOException {
    // Start the server
    server.startServer();
    assertTrue(server.isRunning(), "Server should be running after start");

    // Stop the server
    server.stopServer();
    assertFalse(server.isRunning(), "Server should not be running after stop");
  }

  @Test
  @DisplayName("Test JavaScript code formatting")
  void testJavaScriptFormatting() throws IOException {
    // Sample JavaScript code with formatting issues
    String jsCode = "function test(){    return 1+2;}";

    // Format the code
    String formattedCode = server.formatCode(jsCode, false);

    // Verify formatting improved the code
    assertNotNull(formattedCode, "Formatted code should not be null");
    assertNotEquals(jsCode, formattedCode, "Code should be changed");
    assertTrue(formattedCode.contains("function test()"), "Should format function declaration");
    assertTrue(formattedCode.contains("return 1 + 2"), "Should add spaces around operators");
  }

  @Test
  @DisplayName("Test React JSX formatting")
  void testReactFormatting() throws IOException {
    // Sample React code with formatting issues
    String reactCode =
            "function Component(){    return (<div><h1>Hello</h1><p>World</p></div>);}";

    // Format the code
    String formattedCode = server.formatCode(reactCode, true);

    // Verify basic improvements
    assertNotNull(formattedCode, "Formatted code should not be null");
    assertNotEquals(reactCode, formattedCode, "Code should be changed");
    assertTrue(formattedCode.contains("function Component()"), "Should format function declaration");

    // Should have more whitespace
    assertTrue(
            formattedCode.length() > reactCode.length(),
            "Formatted code should be longer due to added whitespace");
  }

  @Test
  @DisplayName("Test JavaScript code analysis")
  void testJavaScriptAnalysis() throws IOException {
    // Sample JavaScript code with lint issues
    String jsCode = "function test() { var unused = 5; console.log('Hello') }";

    // Analyze the code
    List<NodeJsServer.LintIssue> issues = server.analyzeCode(jsCode, false);

    // Should find at least one issue
    assertFalse(issues.isEmpty(), "Should find at least one issue");

    // Verify typical issues are detected (exact issues depend on ESLint config)
    boolean foundPotentialIssue = issues.stream().anyMatch(issue ->
            issue.getMessage().contains("unused") ||
                    issue.getMessage().contains("semicolon") ||
                    issue.getMessage().contains("missing"));

    assertTrue(foundPotentialIssue, "Should find common JavaScript issues");
  }

  @Test
  @DisplayName("Test React hook dependency analysis")
  void testReactHookAnalysis() throws IOException {
    // Sample React code with hook dependency issue
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

    // Analyze the code
    List<NodeJsServer.LintIssue> issues = server.analyzeCode(reactCode, true);

    // Should find at least one issue
    assertFalse(issues.isEmpty(), "Should find at least one issue");

    // Check for hooks dependency warning
    boolean foundHooksDependencyIssue = issues.stream()
            .anyMatch(issue -> issue.getRuleId().equals("react-hooks/exhaustive-deps"));

    assertTrue(foundHooksDependencyIssue, "Should detect React hooks dependency issue");
  }

  @Test
  @DisplayName("Test handling invalid code")
  void testHandleInvalidCode() throws IOException {
    // Invalid JavaScript code with syntax error
    String invalidCode = "function test( {";

    try {
      // Should not throw exception on formatting
      String formattedCode = server.formatCode(invalidCode, false);

      // Since Prettier will fail, original code should be returned
      assertEquals(invalidCode, formattedCode, "Should return original code when formatting fails");

      // Analysis should detect the syntax error
      List<NodeJsServer.LintIssue> issues = server.analyzeCode(invalidCode, false);

      // Should find syntax error
      assertFalse(issues.isEmpty(), "Should find syntax error");

      // Check for syntax error message
      boolean foundSyntaxError = issues.stream()
              .anyMatch(issue ->
                      issue.getSeverity().equals("error") &&
                              (issue.getRuleId().equals("syntax-error") ||
                                      issue.getMessage().contains("Parsing error")));

      assertTrue(foundSyntaxError, "Should detect syntax error in invalid code");
    } catch (IOException e) {
      fail("Exception should not be thrown: " + e.getMessage());
    }
  }

  @Test
  @DisplayName("Test server configuration")
  void testServerConfiguration() throws IOException {
    Map<String, Object> config = new HashMap<>();
    config.put("printWidth", 120);
    config.put("tabWidth", 4);
    config.put("singleQuote", true);

    try {
      // Configure the server
      server.configure(config);

      // Verify configuration works by formatting code
      String testCode = "function test() { return \"hello\"; }";
      String formattedCode = server.formatCode(testCode, false);

      // Should use single quotes per config
      assertTrue(formattedCode.contains("'hello'"), "Should apply single quote configuration");
    } catch (IOException e) {
      fail("Exception should not be thrown: " + e.getMessage());
    }
  }
}