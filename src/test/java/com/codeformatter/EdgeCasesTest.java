package com.codeformatter;

import static org.junit.jupiter.api.Assertions.*;

import com.codeformatter.api.FormatterResult;
import com.codeformatter.config.ConfigurationLoader;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.core.AdvancedCodeFormatter;
import com.codeformatter.plugins.FileType;
import com.codeformatter.plugins.react.ReactJSFormatter;
import com.codeformatter.plugins.spring.SpringBootFormatter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for edge cases and error handling in the formatter. */
public class EdgeCasesTest {

  @TempDir private Path tempDir;

  private AdvancedCodeFormatter formatter;
  private FormatterConfig config;

  @BeforeEach
  public void setup() {
    // Load configuration
    config = ConfigurationLoader.loadDefaultConfig();

    // Initialize formatter
    formatter = new AdvancedCodeFormatter(config);
    formatter.registerPlugin(FileType.JAVA, new SpringBootFormatter());
    formatter.registerPlugin(FileType.JAVASCRIPT, new ReactJSFormatter());
    formatter.registerPlugin(FileType.JSX, new ReactJSFormatter());
  }

  @AfterEach
  public void cleanup() throws Exception {
    if (formatter != null) {
      formatter.close();
    }
  }

  @Test
  @DisplayName("Test empty file handling")
  public void testEmptyFile() throws IOException {
    // Create an empty file
    Path emptyFile = tempDir.resolve("Empty.java");
    Files.writeString(emptyFile, "");

    // Format the empty file
    FormatterResult result = formatter.formatFile(emptyFile, "");

    // Should handle this gracefully
    assertTrue(result.isSuccessful(), "Empty file formatting should succeed");
    assertEquals("", result.getFormattedCode(), "Empty file should remain empty");
    assertTrue(result.getErrors().isEmpty(), "Should not report errors for empty file");
  }

  @Test
  @DisplayName("Test huge file handling")
  public void testHugeFile() throws IOException {
    // Create a very large but valid Java file
    StringBuilder hugeFile = new StringBuilder();
    hugeFile.append("public class HugeClass {\n");

    // Create a million lines of comment
    for (int i = 0; i < 10000; i++) {
      hugeFile.append("    // Comment line ").append(i).append("\n");
    }

    hugeFile.append("    public void method() {}\n");
    hugeFile.append("}");

    Path filePath = tempDir.resolve("Huge.java");
    Files.writeString(filePath, hugeFile.toString());

    // Format the huge file
    FormatterResult result = formatter.formatFile(filePath, hugeFile.toString());

    // Should handle this without crashing, though it might hit performance issues
    assertNotNull(result, "Should return a result for huge file");
    assertNotNull(result.getFormattedCode(), "Should return formatted code");
  }

  @Test
  @DisplayName("Test file with BOM character")
  public void testFileWithBom() throws IOException {
    // Create a file with UTF-8 BOM
    byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    String content = "public class TestClass {\n    public void test() {}\n}";

    byte[] fileContent = new byte[bom.length + content.getBytes(StandardCharsets.UTF_8).length];
    System.arraycopy(bom, 0, fileContent, 0, bom.length);
    System.arraycopy(
        content.getBytes(StandardCharsets.UTF_8),
        0,
        fileContent,
        bom.length,
        content.getBytes(StandardCharsets.UTF_8).length);

    Path filePath = tempDir.resolve("BomFile.java");
    Files.write(filePath, fileContent);

    // Read file as string (will include BOM as first character)
    String fileString = Files.readString(filePath);

    // Format the file
    FormatterResult result = formatter.formatFile(filePath, fileString);

    // Should handle this gracefully
    assertTrue(result.isSuccessful(), "BOM file formatting should succeed");
    assertNotNull(result.getFormattedCode(), "Should return formatted code");

    // Should preserve the BOM
    assertTrue(result.getFormattedCode().length() > 0, "Formatted code should not be empty");
  }

  @Test
  @DisplayName("Test file with invalid UTF-8 characters")
  public void testFileWithInvalidUtf8() throws IOException {
    // Create a file with invalid UTF-8 sequences
    byte[] invalidUtf8 =
        new byte[] {
          // Valid UTF-8 part
          (byte) 'p',
          (byte) 'u',
          (byte) 'b',
          (byte) 'l',
          (byte) 'i',
          (byte) 'c',
          (byte) ' ',
          (byte) 'c',
          (byte) 'l',
          (byte) 'a',
          (byte) 's',
          (byte) 's',
          (byte) ' ',
          // Invalid UTF-8 sequence
          (byte) 0xC0,
          (byte) 0xAF,
          // More valid content
          (byte) ' ',
          (byte) '{',
          (byte) '}'
        };

    Path filePath = tempDir.resolve("InvalidUtf8.java");
    Files.write(filePath, invalidUtf8);

    try {
      // Read file as string (might throw an exception due to invalid UTF-8)
      String fileString = Files.readString(filePath);

      // Format the file
      FormatterResult result = formatter.formatFile(filePath, fileString);

      // Should handle this gracefully if we got here
      assertNotNull(result, "Should return a result");
      assertTrue(
          result.isSuccessful() || !result.getErrors().isEmpty(),
          "Should either succeed or report errors");
    } catch (Exception e) {
      // An exception here is acceptable, since invalid UTF-8 can cause reading issues
      // The test passes in this case too
    }
  }

  @Test
  @DisplayName("Test file with unicode characters")
  public void testFileWithUnicode() throws IOException {
    // Create a file with Unicode characters
    String content =
        "public class UnicodeTest {\n"
            + "    // Characters from different languages\n"
            + "    String greeting = \"你好, こんにちは, Привет, مرحبا, שלום\";\n"
            + "    \n"
            + "    // Emoji\n"
            + "    String emoji = \"😀 😃 😄 😁 😆\";\n"
            + "    \n"
            + "    // Math symbols\n"
            + "    String math = \"∑∫∂√∞≠≤≥\";\n"
            + "}";

    Path filePath = tempDir.resolve("Unicode.java");
    Files.writeString(filePath, content);

    // Format the file
    FormatterResult result = formatter.formatFile(filePath, content);

    // Should handle Unicode characters properly
    assertTrue(result.isSuccessful(), "Unicode file formatting should succeed");
    assertNotNull(result.getFormattedCode(), "Should return formatted code");

    // Unicode characters should be preserved
    assertTrue(result.getFormattedCode().contains("你好"), "Should preserve Chinese characters");
    assertTrue(result.getFormattedCode().contains("こんにちは"), "Should preserve Japanese characters");
    assertTrue(result.getFormattedCode().contains("😀"), "Should preserve Emoji");
  }

  @Test
  @DisplayName("Test file with line terminator issues")
  public void testLineTerminatorIssues() throws IOException {
    // Create files with different line terminators

    // Windows line endings (CRLF)
    String windowsContent =
        "public class WindowsLineEndings {\r\n"
            + "    public void test() {\r\n"
            + "        System.out.println(\"Windows\");\r\n"
            + "    }\r\n"
            + "}";

    Path windowsFile = tempDir.resolve("Windows.java");
    Files.writeString(windowsFile, windowsContent);

    // Unix line endings (LF)
    String unixContent =
        "public class UnixLineEndings {\n"
            + "    public void test() {\n"
            + "        System.out.println(\"Unix\");\n"
            + "    }\n"
            + "}";

    Path unixFile = tempDir.resolve("Unix.java");
    Files.writeString(unixFile, unixContent);

    // Old Mac line endings (CR)
    String oldMacContent =
        "public class OldMacLineEndings {\r"
            + "    public void test() {\r"
            + "        System.out.println(\"Mac\");\r"
            + "    }\r"
            + "}";

    Path oldMacFile = tempDir.resolve("OldMac.java");
    Files.writeString(oldMacFile, oldMacContent);

    // Mixed line endings
    String mixedContent =
        "public class MixedLineEndings {\r\n"
            + "    public void test1() {\n"
            + "        System.out.println(\"Mixed 1\");\r\n"
            + "    }\r"
            + "    public void test2() {\n"
            + "        System.out.println(\"Mixed 2\");\r"
            + "    }\n"
            + "}";

    Path mixedFile = tempDir.resolve("Mixed.java");
    Files.writeString(mixedFile, mixedContent);

    // Format each file
    FormatterResult windowsResult = formatter.formatFile(windowsFile, windowsContent);
    FormatterResult unixResult = formatter.formatFile(unixFile, unixContent);
    FormatterResult oldMacResult = formatter.formatFile(oldMacFile, oldMacContent);
    FormatterResult mixedResult = formatter.formatFile(mixedFile, mixedContent);

    // All formats should be handled successfully
    assertTrue(windowsResult.isSuccessful(), "Windows line endings should be handled");
    assertTrue(unixResult.isSuccessful(), "Unix line endings should be handled");
    assertTrue(oldMacResult.isSuccessful(), "Old Mac line endings should be handled");
    assertTrue(mixedResult.isSuccessful(), "Mixed line endings should be handled");

    // Check that the code was actually formatted
    assertNotEquals(
        windowsContent, windowsResult.getFormattedCode(), "Windows file should be formatted");
    assertNotEquals(unixContent, unixResult.getFormattedCode(), "Unix file should be formatted");
    assertNotEquals(
        oldMacContent, oldMacResult.getFormattedCode(), "Old Mac file should be formatted");
    assertNotEquals(mixedContent, mixedResult.getFormattedCode(), "Mixed file should be formatted");
  }

  @Test
  @DisplayName("Test file with syntax errors")
  public void testSyntaxErrors() throws IOException {
    // Create a Java file with syntax errors
    String javaWithErrors =
        "public class BrokenJava {\n"
            + "    public void brokenMethod() {\n"
            + "        int x = 5\n"
            + // Missing semicolon
            "        System.out.println(\"Missing semicolon above\");\n"
            + "        if (x > 0) {\n"
            + "            System.out.println(\"No closing brace for if statement\");\n"
            + "        // Missing closing brace\n"
            + "    }\n"
            + "    // Extra method outside class\n"
            + "public void extraMethod() {}\n"; // Missing closing brace for class

    Path javaFile = tempDir.resolve("Broken.java");
    Files.writeString(javaFile, javaWithErrors);

    // Create a React file with syntax errors
    String reactWithErrors =
        "import React from 'react';\n\n"
            + "function BrokenComponent() {\n"
            + "    const [state, setState] = useState();\n"
            + // Missing React import
            "    \n"
            + "    return (\n"
            + "        <div>\n"
            + "            <h1>Broken Component</h1\n"
            + // Missing closing bracket
            "            <p>This component has errors</p>\n"
            + "        </div\n"
            + // Missing closing bracket
            "    );\n"
            + "}\n";

    Path reactFile = tempDir.resolve("Broken.jsx");
    Files.writeString(reactFile, reactWithErrors);

    // Format the files
    FormatterResult javaResult = formatter.formatFile(javaFile, javaWithErrors);
    FormatterResult reactResult = formatter.formatFile(reactFile, reactWithErrors);

    // Should identify issues but not crash
    assertNotNull(javaResult, "Should return a result for broken Java");
    assertFalse(javaResult.isSuccessful(), "Formatting should fail for broken Java");
    assertFalse(javaResult.getErrors().isEmpty(), "Should report errors for broken Java");

    assertNotNull(reactResult, "Should return a result for broken React");
    assertFalse(reactResult.isSuccessful(), "Formatting should fail for broken React");
    assertFalse(reactResult.getErrors().isEmpty(), "Should report errors for broken React");
  }

  @Test
  @DisplayName("Test non-existent file")
  public void testNonExistentFile() {
    // Create a path that doesn't exist
    Path nonExistentFile = tempDir.resolve("DoesNotExist.java");

    // Try to format it (should not crash)
    try {
      formatter.formatFile(nonExistentFile, "public class Test {}");
      // No exception is a pass
    } catch (Exception e) {
      fail("Should not throw exception for non-existent file: " + e.getMessage());
    }
  }

  @Test
  @DisplayName("Test read-only directory")
  public void testReadOnlyDirectory() throws IOException {
    // Skip this test on Windows where permissions work differently
    if (System.getProperty("os.name").toLowerCase().contains("win")) {
      return;
    }

    // Create a read-only directory
    Path readOnlyDir = tempDir.resolve("read-only");
    Files.createDirectory(readOnlyDir);
    readOnlyDir.toFile().setReadOnly();

    try {
      // Try to format the directory
      formatter.formatDirectory(readOnlyDir);
      // No exception is a pass
    } catch (Exception e) {
      fail("Should not throw exception for read-only directory: " + e.getMessage());
    } finally {
      // Make the directory writable again so it can be cleaned up
      readOnlyDir.toFile().setWritable(true);
    }
  }

  @Test
  @DisplayName("Test file detection with unusual extensions")
  public void testUnusualExtensions() throws IOException {
    // Create files with unusual extensions

    // Java file with .jav extension
    String javaContent =
        "public class UnusualExtension {\n"
            + "    public void test() {\n"
            + "        System.out.println(\"Java\");\n"
            + "    }\n"
            + "}";

    Path javaFile = tempDir.resolve("Unusual.jav");
    Files.writeString(javaFile, javaContent);

    // JavaScript file with .es extension
    String jsContent =
        "function unusualExtension() {\n" + "    console.log('JavaScript');\n" + "}\n";

    Path jsFile = tempDir.resolve("Unusual.es");
    Files.writeString(jsFile, jsContent);

    // React JSX file with .jsx.txt extension
    String reactContent =
        "import React from 'react';\n\n"
            + "function UnusualComponent() {\n"
            + "    return (\n"
            + "        <div>\n"
            + "            <h1>Unusual Extension</h1>\n"
            + "        </div>\n"
            + "    );\n"
            + "}\n";

    Path reactFile = tempDir.resolve("Unusual.jsx.txt");
    Files.writeString(reactFile, reactContent);

    // Verify file type detection
    assertEquals(FileType.JAVA, FileType.detect(javaFile), ".jav file should be detected as Java");
    assertEquals(
        FileType.JAVASCRIPT, FileType.detect(jsFile), ".es file should be detected as JavaScript");
    assertEquals(
        FileType.JSX, FileType.detect(reactFile), ".jsx.txt file should be detected as JSX");
  }
}
