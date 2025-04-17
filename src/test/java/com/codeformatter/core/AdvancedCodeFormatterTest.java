package com.codeformatter.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;

import com.codeformatter.api.FormatterResult;
import com.codeformatter.plugins.FileType;

/**
 * Tests for the AdvancedCodeFormatter core class.
 */
public class AdvancedCodeFormatterTest extends BaseFormatterTest {

    @Test
    @DisplayName("Should format Java file successfully")
    public void testFormatJavaFile() throws Exception {
        String javaCode = "package com.example;\n\npublic class   BadlyFormatted    {\n" +
                "    public void   method(  ) {\n" +
                "        System.out.println(  \"Hello World\"    );\n" +
                "    }\n" +
                "}";

        Path javaFile = createTestFile("BadlyFormatted.java", javaCode);

        FormatterResult result = formatter.formatFile(javaFile, javaCode);

        assertSuccessfulFormatting(result, javaCode);
    }

    @Test
    @DisplayName("Should format React JS file successfully")
    public void testFormatReactFile() throws Exception {
        String reactCode = "import React from 'react';\n\n" +
                "function BadComponent( ) {\n" +
                "    return (\n" +
                "        <div    className=\"container\"   >\n" +
                "            <h1>Hello    World</h1>\n" +
                "        </div>\n" +
                "    );\n" +
                "}\n\n" +
                "export default BadComponent;";

        Path reactFile = createTestFile("BadComponent.jsx", reactCode);

        FormatterResult result = formatter.formatFile(reactFile, reactCode);

        assertSuccessfulFormatting(result, reactCode);
    }

    @Test
    @DisplayName("Should handle unsupported file types gracefully")
    public void testUnsupportedFileType() throws Exception {
        String cssCode = ".container { \n  color: red;\n}";
        Path cssFile = createTestFile("styles.css", cssCode);

        FormatterResult result = formatter.formatFile(cssFile, cssCode);

        assertFalse(result.isSuccessful(), "Formatting unsupported file type should not be successful");
        assertContainsError(result, "No plugin registered for file type");
    }

    @Test
    @DisplayName("Should format multiple files in a directory")
    public void testFormatDirectory() throws Exception {
        createTestFile("File1.java", "public class File1 { void   method() {} }");
        createTestFile("File2.java", "public class File2 { void   method() {} }");
        createTestFile("Component.jsx", "function Component() { return <div>   Hello</div>; }");

        Map<Path, FormatterResult> results = formatter.formatDirectory(tempDir);

        assertEquals(3, results.size(), "Should format all three files");
        assertTrue(results.values().stream().allMatch(FormatterResult::isSuccessful),
                "All formatting operations should be successful");
    }

    @Test
    @DisplayName("Should handle file type detection correctly")
    public void testFileTypeDetection() throws Exception {

        Path javaFile = createTestFile("Test.java", "public class Test {}");
        assertEquals(FileType.JAVA, FileType.detect(javaFile));


        Path jsxFile = createTestFile("Component.jsx", "function Component() { return <div>Test</div>; }");
        assertEquals(FileType.JSX, FileType.detect(jsxFile));


        Path jsFile = createTestFile("script.js", "function test() { return 42; }");
        assertEquals(FileType.JAVASCRIPT, FileType.detect(jsFile));

        Path tsFile = createTestFile("types.ts", "interface User { name: string; age: number; }");
        assertEquals(FileType.TYPESCRIPT, FileType.detect(tsFile));

        Path tsxFile = createTestFile("App.tsx", "const App: React.FC = () => <div>Hello</div>;");
        assertEquals(FileType.TSX, FileType.detect(tsxFile));
    }

    @Test
    @DisplayName("Should handle syntax errors in code")
    public void testHandleSyntaxErrors() throws Exception {

        String invalidJavaCode = "public class Test { invalid syntax here; }";
        Path javaFile = createTestFile("InvalidSyntax.java", invalidJavaCode);

        FormatterResult result = formatter.formatFile(javaFile, invalidJavaCode);

        assertFalse(result.isSuccessful(), "Formatting code with syntax errors should not be successful");
        assertContainsError(result, "Failed to parse");
    }

    @Test
    @DisplayName("Should register and use plugins correctly")
    public void testPluginRegistration() {
        assertTrue(formatter.hasPluginFor(FileType.JAVA), "Should have plugin for Java");
        assertTrue(formatter.hasPluginFor(FileType.JAVASCRIPT), "Should have plugin for JavaScript");
        assertTrue(formatter.hasPluginFor(FileType.JSX), "Should have plugin for JSX");
        assertTrue(formatter.hasPluginFor(FileType.TYPESCRIPT), "Should have plugin for TypeScript");
        assertTrue(formatter.hasPluginFor(FileType.TSX), "Should have plugin for TSX");
        assertEquals(5, formatter.getPluginCount(), "Should have 5 plugins registered");
    }
}