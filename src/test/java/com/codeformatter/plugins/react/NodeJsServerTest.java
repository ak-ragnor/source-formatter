package com.codeformatter.plugins.react;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tests for the NodeJsServer class. These tests require Node.js to be installed on the system. */
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
  void testServerStartAndStop() {
    try {
      // Start the server
      server.startServer();
      assertTrue(server.isRunning(), "Server should be running after start");

      // Stop the server
      server.stopServer();
      assertFalse(server.isRunning(), "Server should not be running after stop");
    } catch (IOException e) {
      fail("Exception should not be thrown: " + e.getMessage());
    }
  }

  @Test
  @DisplayName("Test code formatting")
  void testFormatCode() {
    try {
      // Sample JavaScript code with formatting issues
      String jsCode = "function test(){    return 1+2;}";
      String expectedFormattedCode = "function test() {\n  return 1 + 2;\n}\n";

      String formattedCode = server.formatCode(jsCode, false);

      // Normalize line endings for cross-platform tests
      formattedCode = formattedCode.replace("\r\n", "\n");
      expectedFormattedCode = expectedFormattedCode.replace("\r\n", "\n");

      assertEquals(
          expectedFormattedCode, formattedCode, "Formatted code should match expected output");
    } catch (IOException e) {
      fail("Exception should not be thrown: " + e.getMessage());
    }
  }

  @Test
  @DisplayName("Test React JSX formatting")
  void testFormatReactCode() {
    try {
      // Sample React code with formatting issues
      String reactCode =
          "function Component(){    return (<div><h1>Hello</h1><p>World</p></div>);}";

      String formattedCode = server.formatCode(reactCode, true);

      // We'll just check some basic formatting improvements
      assertTrue(formattedCode.contains("function Component()"), "Should preserve function name");
      assertTrue(formattedCode.contains("<div>"), "Should preserve div tag");
      assertTrue(formattedCode.contains("<h1>"), "Should preserve h1 tag");
      assertTrue(formattedCode.contains("<p>"), "Should preserve p tag");

      // The formatted code should have more whitespace
      assertTrue(
          formattedCode.length() > reactCode.length(),
          "Formatted code should be longer due to added whitespace");
    } catch (IOException e) {
      fail("Exception should not be thrown: " + e.getMessage());
    }
  }

  @Test
  @DisplayName("Test code analysis")
  void testAnalyzeCode() {
    try {
      // Sample JavaScript code with lint issues
      String jsCode = "function test() { var unused = 5; console.log('Hello') }";

      List<NodeJsServer.LintIssue> issues = server.analyzeCode(jsCode, false);

      // Should find at least one issue (missing semicolon, unused variable)
      assertFalse(issues.isEmpty(), "Should find at least one issue");

      // Verify issue details
      boolean foundUnusedVar = false;
      boolean foundMissingSemicolon = false;

      for (NodeJsServer.LintIssue issue : issues) {
        if (issue.getMessage().contains("'unused' is assigned a value but never used")) {
          foundUnusedVar = true;
        }
        if (issue.getMessage().contains("semicolon")) {
          foundMissingSemicolon = true;
        }
      }

      assertTrue(
          foundUnusedVar || foundMissingSemicolon,
          "Should find either unused variable or missing semicolon issues");
    } catch (IOException e) {
      fail("Exception should not be thrown: " + e.getMessage());
    }
  }

  @Test
  @DisplayName("Test React code analysis")
  void testAnalyzeReactCode() {
    try {
      // Sample React code with hooks dependency issue
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

      List<NodeJsServer.LintIssue> issues = server.analyzeCode(reactCode, true);

      // Should find at least one issue (hooks dependencies)
      assertFalse(issues.isEmpty(), "Should find at least one issue");

      // Check for hooks dependency warning
      boolean foundHooksDependencyIssue = false;
      for (NodeJsServer.LintIssue issue : issues) {
        if (issue.getRuleId().equals("react-hooks/exhaustive-deps")) {
          foundHooksDependencyIssue = true;
          break;
        }
      }

      assertTrue(foundHooksDependencyIssue, "Should detect React hooks dependency issue");
    } catch (IOException e) {
      fail("Exception should not be thrown: " + e.getMessage());
    }
  }

  @Test
  @DisplayName("Test handling invalid code")
  void testHandleInvalidCode() {
    try {
      // Invalid JavaScript code with syntax error
      String invalidCode = "function test( {";

      // Should not throw exception on formatting
      String formattedCode = server.formatCode(invalidCode, false);
      // Since Prettier will fail, original code should be returned
      assertEquals(invalidCode, formattedCode, "Should return original code when formatting fails");

      // Analysis should detect the syntax error
      List<NodeJsServer.LintIssue> issues = server.analyzeCode(invalidCode, false);

      // Should find syntax error
      assertFalse(issues.isEmpty(), "Should find syntax error");

      boolean foundSyntaxError = false;
      for (NodeJsServer.LintIssue issue : issues) {
        if (issue.getSeverity().equals("error")
            && (issue.getRuleId().equals("syntax-error")
                || issue.getMessage().contains("Parsing error"))) {
          foundSyntaxError = true;
          break;
        }
      }

      assertTrue(foundSyntaxError, "Should detect syntax error in invalid code");
    } catch (IOException e) {
      fail("Exception should not be thrown: " + e.getMessage());
    }
  }
}
