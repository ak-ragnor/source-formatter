package com.codeformatter.plugins.spring.analyzers;

import static org.junit.jupiter.api.Assertions.*;

import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for individual Spring code analyzers.
 *
 * <p>These tests verify that each analyzer correctly identifies specific issues in Spring code,
 * independent of the full formatting pipeline. This allows for better isolation and debugging of
 * analyzer behavior.
 */
public class AnalyzersTest {

  /** Creates a test configuration with specific settings for testing. */
  private FormatterConfig _createTestConfig() {
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
  @DisplayName("MethodSizeAnalyzer should correctly identify large methods")
  public void testMethodSizeAnalyzer() {
    // Create a test configuration
    FormatterConfig config = _createTestConfig();
    MethodSizeAnalyzer analyzer = new MethodSizeAnalyzer(config);

    // Create a test compilation unit with a large method
    String javaCode =
        "public class TestClass {\n"
            + "    public void largeMethod() {\n"
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
            + "    }\n"
            + "    \n"
            + "    public void smallMethod() {\n"
            + "        System.out.println(\"This is a small method\");\n"
            + "    }\n"
            + "}";

    JavaParser parser = new JavaParser();
    CompilationUnit cu = parser.parse(javaCode).getResult().get();

    // Analyze the code
    AnalyzerResult result = analyzer.analyze(cu);

    // Verify errors were detected
    assertFalse(result.getErrors().isEmpty(), "Should detect errors");

    // Verify the specific error for method length
    boolean foundMethodLengthError =
        result.getErrors().stream()
            .anyMatch(
                error ->
                    error.getMessage().contains("Method 'largeMethod' is too long")
                        && error.getSeverity() == Severity.ERROR);

    assertTrue(foundMethodLengthError, "Should detect that 'largeMethod' is too long");

    // Verify only one method had the error (smallMethod should not trigger the error)
    long methodLengthErrorCount =
        result.getErrors().stream()
            .filter(error -> error.getMessage().contains("too long"))
            .count();

    assertEquals(1, methodLengthErrorCount, "Only one method should be flagged as too long");
  }

  @Test
  @DisplayName("SpringComponentAnalyzer should detect Spring dependency injection issues")
  public void testSpringComponentAnalyzer() {
    // Create a test configuration
    FormatterConfig config = _createTestConfig();
    SpringComponentAnalyzer analyzer = new SpringComponentAnalyzer(config);

    // Create a test compilation unit with a Spring service using field injection
    String javaCode =
        "import org.springframework.beans.factory.annotation.Autowired;\n"
            + "import org.springframework.stereotype.Service;\n\n"
            + "@Service\n"
            + "public class TestService {\n"
            + "    @Autowired\n"
            + "    private TestRepository repository;\n"
            + "    \n"
            + "    @Autowired\n"
            + "    public TestClient client; // Should be private\n"
            + "    \n"
            + "    public void doSomething() {\n"
            + "        repository.findData();\n"
            + "        client.sendRequest();\n"
            + "    }\n"
            + "}\n\n"
            + "class TestRepository {\n"
            + "    public void findData() {}\n"
            + "}\n\n"
            + "class TestClient {\n"
            + "    public void sendRequest() {}\n"
            + "}";

    JavaParser parser = new JavaParser();
    CompilationUnit cu = parser.parse(javaCode).getResult().get();

    // Analyze the code
    AnalyzerResult result = analyzer.analyze(cu);
    List<FormatterError> errors = result.getErrors();

    // Verify errors were detected
    assertFalse(errors.isEmpty(), "Should detect errors");

    // Check for field injection issue
    boolean foundInjectionError =
        errors.stream().anyMatch(error -> error.getMessage().contains("Field injection detected"));
    assertTrue(foundInjectionError, "Should detect field injection issue");

    // Check for visibility issue
    boolean foundVisibilityError =
        errors.stream()
            .anyMatch(error -> error.getMessage().contains("Autowired field should be private"));
    assertTrue(foundVisibilityError, "Should detect autowired field visibility issue");

    // Test refactoring capabilities
    assertTrue(analyzer.canAutoFix(), "Analyzer should support auto-fixing");

    // Apply refactoring
    RefactoringResult refactoringResult = analyzer.applyRefactoring(cu);

    // Verify refactorings were applied
    assertFalse(refactoringResult.getAppliedRefactorings().isEmpty(), "Should apply refactorings");

    // Get the refactored code as string for manual inspection if needed
    String refactoredCode = cu.toString();

    // Verify constructor was added
    assertTrue(refactoredCode.contains("public TestService("), "Should add constructor");

    // Verify constructor parameters
    assertTrue(
        refactoredCode.contains("TestRepository repository"),
        "Constructor should include repository parameter");
    assertTrue(
        refactoredCode.contains("TestClient client"),
        "Constructor should include client parameter");

    // Verify field assignments
    assertTrue(
        refactoredCode.contains("this.repository = repository"),
        "Should include field assignments in constructor");
    assertTrue(
        refactoredCode.contains("this.client = client"),
        "Should include field assignments in constructor");

    // Verify @Autowired was moved to constructor
    boolean constructorHasAutowired =
        refactoredCode.contains("@Autowired") && refactoredCode.contains("public TestService(");
    assertTrue(constructorHasAutowired, "Constructor should have @Autowired annotation");
  }

  @Test
  @DisplayName("DesignPatternAnalyzer should detect design pattern issues")
  public void testDesignPatternAnalyzer() {
    // Create a test configuration
    FormatterConfig config = _createTestConfig();
    DesignPatternAnalyzer analyzer = new DesignPatternAnalyzer(config);

    // Create a test compilation unit with design pattern issues
    String javaCode =
        "public class LargeClass {\n"
            + "    private String field1;\n"
            + "    private String field2;\n"
            + "    private String field3;\n"
            + "    private String field4;\n"
            + "    private String field5;\n"
            + "    private String field6;\n"
            + "    private String field7;\n"
            + "    private String field8;\n"
            + "    private String field9;\n"
            + "    private String field10;\n"
            + "    private String field11;\n"
            + "    \n"
            + "    public LargeClass(String f1, String f2, String f3, String f4, String f5) {\n"
            + "        // Complex constructor\n"
            + "        this.field1 = f1;\n"
            + "        this.field2 = f2;\n"
            + "        this.field3 = f3;\n"
            + "        this.field4 = f4;\n"
            + "        this.field5 = f5;\n"
            + "    }\n"
            + "    \n"
            + "    // Factory-like methods without being a factory\n"
            + "    public Object createObj1() { return new Object(); }\n"
            + "    public Object createObj2() { return new Object(); }\n"
            + "    public Object createObj3() { return new Object(); }\n"
            + "    \n"
            + "    public void processType(String type) {\n"
            + "        // Switch statement that should use Strategy pattern\n"
            + "        switch(type) {\n"
            + "            case \"type1\":\n"
            + "                System.out.println(\"Processing type 1\");\n"
            + "                break;\n"
            + "            case \"type2\":\n"
            + "                System.out.println(\"Processing type 2\");\n"
            + "                break;\n"
            + "            case \"type3\":\n"
            + "                System.out.println(\"Processing type 3\");\n"
            + "                break;\n"
            + "            case \"type4\":\n"
            + "                System.out.println(\"Processing type 4\");\n"
            + "                break;\n"
            + "            default:\n"
            + "                System.out.println(\"Unknown type\");\n"
            + "        }\n"
            + "    }\n"
            + "}";

    JavaParser parser = new JavaParser();
    CompilationUnit cu = parser.parse(javaCode).getResult().get();

    // Analyze the code
    AnalyzerResult result = analyzer.analyze(cu);
    List<FormatterError> errors = result.getErrors();

    // Verify errors were detected
    assertFalse(errors.isEmpty(), "Should detect errors");

    // Check for specific design pattern suggestions

    // Check for factory pattern suggestion
    boolean factoryPatternSuggested =
        errors.stream().anyMatch(error -> error.getMessage().contains("factory-like methods"));
    assertTrue(factoryPatternSuggested, "Should suggest Factory pattern");

    // Check for builder pattern suggestion
    boolean builderPatternSuggested =
        errors.stream()
            .anyMatch(
                error ->
                    error.getMessage().contains("Builder pattern")
                        || error.getSuggestion() != null
                            && error.getSuggestion().contains("Builder"));
    assertTrue(builderPatternSuggested, "Should suggest Builder pattern");

    // Check for strategy pattern suggestion
    boolean strategyPatternSuggested =
        errors.stream()
            .anyMatch(
                error ->
                    error.getMessage().contains("switch statement")
                        && error.getSuggestion() != null
                        && error.getSuggestion().contains("Strategy Pattern"));
    assertTrue(strategyPatternSuggested, "Should suggest Strategy pattern");

    // The DesignPatternAnalyzer doesn't support auto-fixing
    assertFalse(analyzer.canAutoFix(), "Design pattern issues typically need manual refactoring");
  }

  @Test
  @DisplayName("ImportOrganizer should organize imports correctly")
  public void testImportOrganizer() {
    // Create a test configuration
    FormatterConfig config = _createTestConfig();
    ImportOrganizer analyzer = new ImportOrganizer(config);

    // Create a test compilation unit with unorganized imports
    String javaCode =
        "import java.util.List;\n"
            + "import static java.util.Collections.emptyList;\n"
            + "import org.springframework.stereotype.Service;\n"
            + "import java.util.ArrayList;\n"
            + "import com.example.SomeClass;\n"
            + "import java.util.Map;\n"
            + "import org.springframework.beans.factory.annotation.Autowired;\n"
            + "import java.util.HashMap;\n"
            + "\n"
            + "public class Test {\n"
            + "    private List<String> list = emptyList();\n"
            + "}";

    JavaParser parser = new JavaParser();
    CompilationUnit cu = parser.parse(javaCode).getResult().get();

    // Analyze the code
    AnalyzerResult result = analyzer.analyze(cu);

    // Verify issues were detected
    assertFalse(result.getErrors().isEmpty(), "Should detect import organization issues");

    // Test refactoring capability
    assertTrue(analyzer.canAutoFix(), "ImportOrganizer should support auto-fixing");

    // Apply refactoring
    RefactoringResult refactoringResult = analyzer.applyRefactoring(cu);

    // Verify refactorings were applied
    assertFalse(refactoringResult.getAppliedRefactorings().isEmpty(), "Should apply refactorings");

    // Verify specific refactoring type
    boolean importOrganizationApplied =
        refactoringResult.getAppliedRefactorings().stream()
            .anyMatch(r -> r.getType().equals("IMPORT_ORGANIZATION"));
    assertTrue(importOrganizationApplied, "Should perform import organization");

    // Get the refactored code
    String organizedCode = cu.toString();

    // Verify static imports come first
    int staticPos = organizedCode.indexOf("import static");
    int javaPos = organizedCode.indexOf("import java");
    int springPos = organizedCode.indexOf("import org.springframework");
    int examplePos = organizedCode.indexOf("import com.example");

    assertTrue(staticPos < javaPos, "Static imports should come before java imports");
    assertTrue(javaPos < springPos, "Java imports should come before Spring imports");

    // Check for duplicate removal if there were any
    long importLines = organizedCode.lines().filter(line -> line.startsWith("import ")).count();
    long uniqueImportClasses =
        organizedCode
            .lines()
            .filter(line -> line.startsWith("import "))
            .map(
                line -> {
                  // Extract just the class name
                  String importStr = line.substring(line.lastIndexOf('.') + 1);
                  // Remove any semicolon
                  return importStr.replace(";", "");
                })
            .distinct()
            .count();

    assertEquals(importLines, uniqueImportClasses, "Should not have duplicate imports");
  }

  @Test
  @DisplayName("CodeStyleAnalyzer should detect code style issues")
  public void testCodeStyleAnalyzer() {
    FormatterConfig config = _createTestConfig();
    CodeStyleAnalyzer analyzer = new CodeStyleAnalyzer(config);

    String javaCode =
        """
                    public class StyleIssues {
                        public void methodWithLongLine() {
                            String longString = "This line is going to be way too long for most style \
                    guidelines and should trigger a line length warning in our analyzer";
                        }
                       \s
                        public void methodWithBadNaming(int badlyNamed_param) {
                            int snake_case_variable = 5;
                            String PascalCaseVariable = "incorrect";
                        }
                       \s
                        public void methodWithChaining() {
                            String result = "test".trim().toLowerCase().replace("t", "T").concat("ing").substring(0, 4);
                        }
                    }""";

    CompilationUnit cu = new JavaParser().parse(javaCode).getResult().orElseThrow();
    AnalyzerResult analysis = analyzer.analyze(cu);
    List<com.codeformatter.api.error.FormatterError> errors = analysis.getErrors();

    assertEquals(5, errors.size(), "Expected exactly 5 style errors");

    long snakeVarCount =
        errors.stream().filter(e -> e.getMessage().contains("snake_case_variable")).count();
    assertEquals(1, snakeVarCount, "Should detect one snake_case variable naming issue");

    long pascalVarCount =
        errors.stream().filter(e -> e.getMessage().contains("PascalCaseVariable")).count();
    assertEquals(1, pascalVarCount, "Should detect one PascalCase variable naming issue");

    long lineLengthCount =
        errors.stream()
            .filter(e -> e.getMessage().toLowerCase().contains("exceed line length"))
            .count();
    assertEquals(1, lineLengthCount, "Should detect one line‑length issue");

    long chainCount =
        errors.stream()
            .filter(e -> e.getMessage().toLowerCase().contains("long method chain"))
            .count();
    assertEquals(2, chainCount, "Should detect two long method chain issues");

    assertTrue(analyzer.canAutoFix(), "CodeStyleAnalyzer should support auto-fixing");
  }
}
