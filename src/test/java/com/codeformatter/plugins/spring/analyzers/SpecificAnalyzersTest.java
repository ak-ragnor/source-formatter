package com.codeformatter.plugins.spring.analyzers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import com.codeformatter.api.error.FormatterError;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;

/**
 * Tests for specific Spring analyzers.
 */
public class SpecificAnalyzersTest {

    private FormatterConfig createTestConfig() {
        Map<String, Object> generalConfig = new HashMap<>();
        generalConfig.put("indentSize", 4);
        generalConfig.put("lineLength", 100);

        Map<String, Object> springConfig = new HashMap<>();
        springConfig.put("maxMethodLines", 15);
        springConfig.put("maxMethodComplexity", 5);
        springConfig.put("enforceDesignPatterns", true);
        springConfig.put("enforceDependencyInjection", "constructor");

        Map<String, Map<String, Object>> pluginConfigs = new HashMap<>();
        pluginConfigs.put("spring", springConfig);

        return new FormatterConfig(generalConfig, pluginConfigs);
    }

    @Test
    @DisplayName("Test MethodSizeAnalyzer correctly identifies large methods")
    public void testMethodSizeAnalyzer() {
        // Create a method size analyzer
        FormatterConfig config = createTestConfig();
        MethodSizeAnalyzer analyzer = new MethodSizeAnalyzer(config);

        // Create a test compilation unit with a large method
        String javaCode = "public class TestClass {\n" +
                "    public void largeMethod() {\n" +
                "        System.out.println(\"Line 1\");\n" +
                "        System.out.println(\"Line 2\");\n" +
                "        System.out.println(\"Line 3\");\n" +
                "        System.out.println(\"Line 4\");\n" +
                "        System.out.println(\"Line 5\");\n" +
                "        System.out.println(\"Line 6\");\n" +
                "        System.out.println(\"Line 7\");\n" +
                "        System.out.println(\"Line 8\");\n" +
                "        System.out.println(\"Line 9\");\n" +
                "        System.out.println(\"Line 10\");\n" +
                "        System.out.println(\"Line 11\");\n" +
                "        System.out.println(\"Line 12\");\n" +
                "        System.out.println(\"Line 13\");\n" +
                "        System.out.println(\"Line 14\");\n" +
                "        System.out.println(\"Line 15\");\n" +
                "        System.out.println(\"Line 16\");\n" + // Over the limit
                "    }\n" +
                "    \n" +
                "    public void smallMethod() {\n" +
                "        System.out.println(\"This is a small method\");\n" +
                "    }\n" +
                "}";

        JavaParser parser = new JavaParser();
        CompilationUnit cu = parser.parse(javaCode).getResult().get();

        // Test analysis
        AnalyzerResult result = analyzer.analyze(cu);
        assertFalse(result.getErrors().isEmpty(), "Should detect errors");

        boolean foundError = result.getErrors().stream()
                .anyMatch(error -> error.getMessage().contains("Method 'largeMethod' is too long"));

        assertTrue(foundError, "Should detect large method");

        // Test refactoring
        assertTrue(analyzer.canAutoFix(), "Analyzer should support auto-fixing");

        RefactoringResult refactoringResult = analyzer.applyRefactoring(cu);
        assertFalse(refactoringResult.getAppliedRefactorings().isEmpty(),
                "Should apply refactorings");

        boolean foundRefactoring = refactoringResult.getAppliedRefactorings().stream()
                .anyMatch(r -> r.getType().equals("METHOD_EXTRACTION"));

        assertTrue(foundRefactoring, "Should perform method extraction");
    }

    @Test
    @DisplayName("Test SpringComponentAnalyzer detects Spring issues")
    public void testSpringComponentAnalyzer() {
        // Create a Spring component analyzer
        FormatterConfig config = createTestConfig();
        SpringComponentAnalyzer analyzer = new SpringComponentAnalyzer(config);

        // Create a test compilation unit with a Spring service using field injection
        String javaCode = "import org.springframework.beans.factory.annotation.Autowired;\n" +
                "import org.springframework.stereotype.Service;\n\n" +
                "@Service\n" +
                "public class TestService {\n" +
                "    @Autowired\n" +
                "    private TestRepository repository;\n" +
                "    \n" +
                "    @Autowired\n" +
                "    public TestClient client; // Should be private\n" +
                "    \n" +
                "    public void doSomething() {\n" +
                "        repository.findData();\n" +
                "        client.sendRequest();\n" +
                "    }\n" +
                "}\n\n" +
                "class TestRepository {\n" +
                "    public void findData() {}\n" +
                "}\n\n" +
                "class TestClient {\n" +
                "    public void sendRequest() {}\n" +
                "}";

        JavaParser parser = new JavaParser();
        CompilationUnit cu = parser.parse(javaCode).getResult().get();

        // Test analysis
        AnalyzerResult result = analyzer.analyze(cu);
        assertFalse(result.getErrors().isEmpty(), "Should detect errors");

        List<FormatterError> errors = result.getErrors();

        // Check for field injection issue
        boolean foundInjectionError = errors.stream()
                .anyMatch(error -> error.getMessage().contains("Field injection detected"));

        assertTrue(foundInjectionError, "Should detect field injection issue");

        // Check for visibility issue
        boolean foundVisibilityError = errors.stream()
                .anyMatch(error -> error.getMessage().contains("Autowired field should be private"));

        assertTrue(foundVisibilityError, "Should detect autowired field visibility issue");

        // Test refactoring
        assertTrue(analyzer.canAutoFix(), "Analyzer should support auto-fixing");

        RefactoringResult refactoringResult = analyzer.applyRefactoring(cu);
        assertFalse(refactoringResult.getAppliedRefactorings().isEmpty(),
                "Should apply refactorings");
    }

    @Test
    @DisplayName("Test DesignPatternAnalyzer detects design pattern issues")
    public void testDesignPatternAnalyzer() {
        // Create a design pattern analyzer
        FormatterConfig config = createTestConfig();
        DesignPatternAnalyzer analyzer = new DesignPatternAnalyzer(config);

        // Create a test compilation unit with design pattern issues
        String javaCode = "public class LargeClass {\n" +
                "    private String field1;\n" +
                "    private String field2;\n" +
                "    private String field3;\n" +
                "    private String field4;\n" +
                "    private String field5;\n" +
                "    private String field6;\n" +
                "    private String field7;\n" +
                "    private String field8;\n" +
                "    private String field9;\n" +
                "    private String field10;\n" +
                "    private String field11;\n" + // Too many fields
                "    \n" +
                "    public LargeClass(String f1, String f2, String f3, String f4, String f5) {\n" +
                "        // Complex constructor\n" +
                "        this.field1 = f1;\n" +
                "        this.field2 = f2;\n" +
                "        this.field3 = f3;\n" +
                "        this.field4 = f4;\n" +
                "        this.field5 = f5;\n" +
                "    }\n" +
                "    \n" +
                "    public void method1() {}\n" +
                "    public void method2() {}\n" +
                "    public void method3() {}\n" +
                "    public void method4() {}\n" +
                "    public void method5() {}\n" +
                "    public void method6() {}\n" +
                "    public void method7() {}\n" +
                "    public void method8() {}\n" +
                "    public void method9() {}\n" +
                "    public void method10() {}\n" +
                "    public void method11() {}\n" +
                "    public void method12() {}\n" +
                "    public void method13() {}\n" +
                "    public void method14() {}\n" +
                "    public void method15() {}\n" +
                "    public void method16() {}\n" + // Too many methods
                "    \n" +
                "    public void processType(String type) {\n" +
                "        // Multiple conditional branches - should use Strategy pattern\n" +
                "        switch(type) {\n" +
                "            case \"type1\":\n" +
                "                System.out.println(\"Processing type 1\");\n" +
                "                break;\n" +
                "            case \"type2\":\n" +
                "                System.out.println(\"Processing type 2\");\n" +
                "                break;\n" +
                "            case \"type3\":\n" +
                "                System.out.println(\"Processing type 3\");\n" +
                "                break;\n" +
                "            case \"type4\":\n" +
                "                System.out.println(\"Processing type 4\");\n" +
                "                break;\n" +
                "            default:\n" +
                "                System.out.println(\"Unknown type\");\n" +
                "        }\n" +
                "    }\n" +
                "    \n" +
                "    // Factory-like methods without being a factory\n" +
                "    public Object createObj1() { return new Object(); }\n" +
                "    public Object createObj2() { return new Object(); }\n" +
                "    public Object createObj3() { return new Object(); }\n" +
                "}";

        JavaParser parser = new JavaParser();
        CompilationUnit cu = parser.parse(javaCode).getResult().get();

        // Test analysis
        AnalyzerResult result = analyzer.analyze(cu);
        assertFalse(result.getErrors().isEmpty(), "Should detect errors");

        List<FormatterError> errors = result.getErrors();

        // Check for single responsibility principle violation
        boolean foundSrpError = errors.stream()
                .anyMatch(error -> error.getMessage().contains("violate the Single Responsibility Principle"));

        assertTrue(foundSrpError, "Should detect Single Responsibility Principle violation");

        // Check for factory pattern suggestion
        boolean foundFactoryError = errors.stream()
                .anyMatch(error -> error.getMessage().contains("factory-like methods"));

        assertTrue(foundFactoryError, "Should suggest Factory pattern");

        // Check for builder pattern suggestion
        boolean foundBuilderError = errors.stream()
                .anyMatch(error -> error.getMessage().contains("Builder pattern"));

        assertTrue(foundBuilderError, "Should suggest Builder pattern");

        // Check for strategy pattern suggestion
        boolean foundStrategyError = errors.stream()
                .anyMatch(error -> error.getMessage().contains("switch statement") &&
                        error.getMessage().contains("Strategy Pattern"));

        assertTrue(foundStrategyError, "Should suggest Strategy pattern");
    }

    @Test
    @DisplayName("Test ImportOrganizer correctly organizes imports")
    public void testImportOrganizer() {
        // Create an import organizer
        FormatterConfig config = createTestConfig();
        ImportOrganizer analyzer = new ImportOrganizer(config);

        // Create a test compilation unit with unorganized imports
        String javaCode = "import java.util.List;\n" +
                "import static java.util.Collections.emptyList;\n" + // Static import should be first
                "import org.springframework.stereotype.Service;\n" +
                "import java.util.ArrayList;\n" + // Java imports should be grouped
                "import com.example.SomeClass;\n" +
                "import java.util.Map;\n" + // Another java import out of order
                "import org.springframework.beans.factory.annotation.Autowired;\n" + // Spring imports should be grouped
                "import java.util.HashMap;\n"; // Another java import out of order

        JavaParser parser = new JavaParser();
        CompilationUnit cu = parser.parse(javaCode).getResult().get();

        // Test analysis
        AnalyzerResult result = analyzer.analyze(cu);
        assertFalse(result.getErrors().isEmpty(), "Should detect issues");

        // Test refactoring
        assertTrue(analyzer.canAutoFix(), "Analyzer should support auto-fixing");

        RefactoringResult refactoringResult = analyzer.applyRefactoring(cu);
        assertFalse(refactoringResult.getAppliedRefactorings().isEmpty(),
                "Should apply refactorings");

        boolean foundRefactoring = refactoringResult.getAppliedRefactorings().stream()
                .anyMatch(r -> r.getType().equals("IMPORT_ORGANIZATION"));

        assertTrue(foundRefactoring, "Should perform import organization");

        // The imports in the CompilationUnit should now be organized
        // Check if static imports are first
        if (!cu.getImports().isEmpty()) {
            assertTrue(cu.getImports().get(0).isStatic(),
                    "First import should be static after organizing");
        }
    }
}