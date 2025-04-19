package com.codeformatter.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for FormatterCli command-line interface. */
public class FormatterCliTest {

  @TempDir private Path tempDir;

  private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
  private final PrintStream originalOut = System.out;
  private final PrintStream originalErr = System.err;

  private Path configFile;
  private Path javaFile;
  private Path reactFile;

  @BeforeEach
  public void setup() throws Exception {
    // Redirect stdout and stderr for testing
    System.setOut(new PrintStream(outContent));
    System.setErr(new PrintStream(errContent));

    // Create a test config file
    configFile = tempDir.resolve(".codeformatter.yml");
    Files.writeString(
        configFile,
        "general:\n"
            + "  indentSize: 2\n"
            + "  lineLength: 80\n"
            + "plugins:\n"
            + "  spring:\n"
            + "    maxMethodLines: 20\n"
            + "  react:\n"
            + "    maxComponentLines: 50\n");

    // Create a test Java file
    javaFile = tempDir.resolve("TestClass.java");
    Files.writeString(
        javaFile,
        "public class   TestClass {\n"
            + "    public void   testMethod(  ) {\n"
            + "        System.out.println(  \"Hello\"    );\n"
            + "    }\n"
            + "}");

    // Create a test React file
    reactFile = tempDir.resolve("TestComponent.jsx");
    Files.writeString(
        reactFile,
        "import React from 'react';\n\n"
            + "function TestComponent( ) {\n"
            + "    return (\n"
            + "        <div    className=\"container\"   >\n"
            + "            <h1>Hello    World</h1>\n"
            + "        </div>\n"
            + "    );\n"
            + "}");
  }

  @AfterEach
  public void restore() {
    System.setOut(originalOut);
    System.setErr(originalErr);
  }

  @Test
  @DisplayName("Test CLI shows help when no args provided")
  public void testNoArgs() {
    // Execute CLI with no arguments
    FormatterCli.main(new String[] {});

    // Verify output
    String output = outContent.toString();
    assertTrue(output.contains("Usage:"), "Should show usage information");
    assertTrue(output.contains("codeformatter init"), "Should show init command");
    assertTrue(output.contains("codeformatter format"), "Should show format command");
  }

  @Test
  @DisplayName("Test CLI version command")
  public void testVersionCommand() {
    // Execute CLI with version argument
    FormatterCli.main(new String[] {"--version"});

    // Verify output
    String output = outContent.toString();
    assertTrue(
        output.contains("Advanced Code Formatter version"), "Should show version information");
  }

  @Test
  @DisplayName("Test CLI init command")
  public void testInitCommand() throws Exception {
    // Delete config file if it exists
    Files.deleteIfExists(configFile);

    // Execute CLI with init command
    FormatterCli.main(new String[] {"init"});

    // Verify output
    String output = outContent.toString();
    assertTrue(
        output.contains("Created configuration file"), "Should confirm config file creation");

    // Verify file was created
    assertTrue(Files.exists(configFile), "Config file should exist");
    String configContent = Files.readString(configFile);
    assertTrue(configContent.contains("general:"), "Config should have general section");
    assertTrue(configContent.contains("plugins:"), "Config should have plugins section");
  }

  @Test
  @DisplayName("Test CLI format command on Java file")
  public void testFormatJavaFile() throws Exception {
    // Get the absolute path of the file
    String filePath = javaFile.toAbsolutePath().toString();

    // Execute CLI with format command
    FormatterCli.main(
        new String[] {"format", filePath, "--config=" + configFile.toAbsolutePath().toString()});

    // Verify output
    String output = outContent.toString();
    assertTrue(
        output.contains("Formatted: ") || output.contains("Successfully formatted"),
        "Should confirm formatting");

    // Verify file was modified
    String formattedContent = Files.readString(javaFile);
    assertFalse(
        formattedContent.contains("public class   TestClass"), "Extra spaces should be removed");
    assertFalse(
        formattedContent.contains("public void   testMethod(  )"),
        "Extra spaces should be removed");
  }

  @Test
  @DisplayName("Test CLI format command on React file")
  public void testFormatReactFile() throws Exception {
    // Get the absolute path of the file
    String filePath = reactFile.toAbsolutePath().toString();

    // Execute CLI with format command
    FormatterCli.main(
        new String[] {"format", filePath, "--config=" + configFile.toAbsolutePath().toString()});

    // Verify output
    String output = outContent.toString();
    assertTrue(
        output.contains("Formatted: ") || output.contains("Successfully formatted"),
        "Should confirm formatting");

    // Verify file was modified
    String formattedContent = Files.readString(reactFile);
    assertFalse(
        formattedContent.contains("function TestComponent( )"), "Extra spaces should be removed");
    assertFalse(
        formattedContent.contains("<div    className=\"container\"   >"),
        "Extra spaces should be removed");
  }

  @Test
  @DisplayName("Test CLI format command on directory")
  public void testFormatDirectory() {
    // Execute CLI with format command on directory
    FormatterCli.main(
        new String[] {
          "format",
          tempDir.toAbsolutePath().toString(),
          "--config=" + configFile.toAbsolutePath().toString()
        });

    // Verify output
    String output = outContent.toString();
    assertTrue(
        output.contains("Found") && output.contains("files to format"),
        "Should find files to format");
    assertTrue(output.contains("Formatting complete"), "Should complete formatting");
  }

  @Test
  @DisplayName("Test CLI check command")
  public void testCheckCommand() throws Exception {
    // First make the files non-compliant
    Files.writeString(
        javaFile,
        "public class   TestClass {\n"
            + "    public void   testMethod(  ) {\n"
            + "        System.out.println(  \"Hello\"    );\n"
            + "    }\n"
            + "}");

    // Execute CLI with check command
    FormatterCli.main(
        new String[] {
          "check",
          tempDir.toAbsolutePath().toString(),
          "--config=" + configFile.toAbsolutePath().toString()
        });

    // Verify output
    String output = outContent.toString();
    assertTrue(output.contains("File needs formatting:"), "Should detect files needing formatting");
    assertTrue(output.contains("Check complete"), "Should complete check");
  }

  @Test
  @DisplayName("Test CLI analyze command")
  public void testAnalyzeCommand() throws Exception {
    // Create a file with issues to analyze
    Path fileWithIssues = tempDir.resolve("IssuesClass.java");
    Files.writeString(
        fileWithIssues,
        "import java.util.List;\n"
            + "import static java.util.Collections.emptyList;\n"
            + // Unorganized imports
            "\n"
            + "public class IssuesClass {\n"
            + "    // Method that's too long\n"
            + "    public void longMethod() {\n"
            + "        System.out.println(\"Line 1\");\n"
            + "        System.out.println(\"Line 2\");\n"
            + "        System.out.println(\"Line 3\");\n"
            + "        System.out.println(\"Line 4\");\n"
            + "        System.out.println(\"Line 5\");\n"
            + "        System.out.println(\"Line 6\");\n"
            + "        System.out.println(\"Line 7\");\n"
            + "        System.out.println(\"Line 8\");\n"
            + "        System.out.println(\"Line 9\");\n"
            + "        System.out.println(\"Line 10\");\n"
            + "        System.out.println(\"Line 11\");\n"
            + "        System.out.println(\"Line 12\");\n"
            + "        System.out.println(\"Line 13\");\n"
            + "        System.out.println(\"Line 14\");\n"
            + "        System.out.println(\"Line 15\");\n"
            + "        System.out.println(\"Line 16\");\n"
            + "        System.out.println(\"Line 17\");\n"
            + "        System.out.println(\"Line 18\");\n"
            + "        System.out.println(\"Line 19\");\n"
            + "        System.out.println(\"Line 20\");\n"
            + "        System.out.println(\"Line 21\");\n"
            + "    }\n"
            + "}");

    // Execute CLI with analyze command
    FormatterCli.main(
        new String[] {
          "analyze",
          fileWithIssues.toAbsolutePath().toString(),
          "--config=" + configFile.toAbsolutePath().toString()
        });

    // Verify output
    String output = outContent.toString();
    assertTrue(output.contains("Issues found:"), "Should detect issues in the file");
    assertTrue(
        output.contains("Method") && output.contains("too long"),
        "Should identify method length issue");
    assertTrue(output.contains("Analysis complete"), "Should complete analysis");
  }

  @Test
  @DisplayName("Test CLI handles invalid commands")
  public void testInvalidCommand() {
    // Execute CLI with invalid command
    FormatterCli.main(new String[] {"invalid-command"});

    // Verify output
    String output = outContent.toString();
    assertTrue(output.contains("Unknown command"), "Should report unknown command");
    assertTrue(output.contains("Usage:"), "Should show usage information");
  }

  @Test
  @DisplayName("Test CLI handles file not found")
  public void testFileNotFound() {
    // Execute CLI with non-existent file
    FormatterCli.main(new String[] {"format", "non-existent-file.java"});

    // Verify output
    String output = outContent.toString();
    assertTrue(output.contains("Error: Path does not exist"), "Should report file not found");
  }
}
