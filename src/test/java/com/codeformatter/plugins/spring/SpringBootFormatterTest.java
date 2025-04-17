package com.codeformatter.plugins.spring;

import org.junit.jupiter.api.BeforeEach;
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
 * Tests for SpringBootFormatter functionality.
 */
public class SpringBootFormatterTest {

    private SpringBootFormatter formatter;
    private FormatterConfig config;

    @TempDir
    private Path tempDir;

    @BeforeEach
    public void setup() {
        // Create config with test settings
        Map<String, Object> generalConfig = new HashMap<>();
        generalConfig.put("indentSize", 4);
        generalConfig.put("lineLength", 100);

        Map<String, Object> springConfig = new HashMap<>();
        springConfig.put("maxMethodLines", 20); // Stricter for testing
        springConfig.put("maxMethodComplexity", 5); // Stricter for testing
        springConfig.put("enforceDesignPatterns", true);
        springConfig.put("enforceDependencyInjection", "constructor");

        Map<String, Map<String, Object>> pluginConfigs = new HashMap<>();
        pluginConfigs.put("spring", springConfig);

        config = new FormatterConfig(generalConfig, pluginConfigs);

        formatter = new SpringBootFormatter();
        formatter.initialize(config);
    }

    @Test
    @DisplayName("Should detect and report method size issues")
    public void testMethodSizeDetection() {
        // Create a class with an overly long method
        String javaCode = "package com.example;\n\n" +
                "public class LongMethodExample {\n" +
                "    public void longMethod() {\n" +
                "        // This method has too many lines\n" +
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
                "        System.out.println(\"Line 16\");\n" +
                "        System.out.println(\"Line 17\");\n" +
                "        System.out.println(\"Line 18\");\n" +
                "        System.out.println(\"Line 19\");\n" +
                "        System.out.println(\"Line 20\");\n" +
                "        System.out.println(\"Line 21\");\n" + // Makes method too long
                "    }\n" +
                "}";

        Path javaFile = tempDir.resolve("LongMethodExample.java");

        // Format and check
        FormatterResult result = formatter.format(javaFile, javaCode);

        // Should detect the method size issue
        assertFalse(result.getErrors().isEmpty(), "Should detect errors");
        assertTrue(result.getErrors().stream()
                        .anyMatch(error -> error.getMessage().contains("too long")),
                "Should detect method length issue");
    }

    @Test
    @DisplayName("Should detect and report method complexity issues")
    public void testMethodComplexityDetection() {
        // Create a class with a complex method (lots of conditionals)
        String javaCode = "package com.example;\n\n" +
                "public class ComplexMethodExample {\n" +
                "    public void complexMethod(int value) {\n" +
                "        // This method has too many branches\n" +
                "        if (value < 0) {\n" +
                "            System.out.println(\"Negative\");\n" +
                "        } else if (value == 0) {\n" +
                "            System.out.println(\"Zero\");\n" +
                "        } else if (value < 10) {\n" +
                "            System.out.println(\"Small\");\n" +
                "        } else if (value < 100) {\n" +
                "            System.out.println(\"Medium\");\n" +
                "        } else {\n" +
                "            System.out.println(\"Large\");\n" +
                "        }\n" +
                "        \n" +
                "        for (int i = 0; i < value; i++) {\n" +
                "            if (i % 2 == 0) {\n" +
                "                System.out.println(\"Even\");\n" +
                "            } else {\n" +
                "                System.out.println(\"Odd\");\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}";

        Path javaFile = tempDir.resolve("ComplexMethodExample.java");

        // Format and check
        FormatterResult result = formatter.format(javaFile, javaCode);

        // Should detect the complexity issue
        assertFalse(result.getErrors().isEmpty(), "Should detect errors");
        assertTrue(result.getErrors().stream()
                        .anyMatch(error -> error.getMessage().contains("too complex")),
                "Should detect method complexity issue");
    }

    @Test
    @DisplayName("Should detect Spring dependency injection style issues")
    public void testDependencyInjectionDetection() {
        // Create a Spring component with field injection (against configured preference)
        String javaCode = "package com.example;\n\n" +
                "import org.springframework.beans.factory.annotation.Autowired;\n" +
                "import org.springframework.stereotype.Service;\n\n" +
                "@Service\n" +
                "public class FieldInjectionService {\n" +
                "    @Autowired\n" + // Field injection when constructor injection is preferred
                "    private SomeDependency dependency;\n" +
                "    \n" +
                "    public void doSomething() {\n" +
                "        dependency.process();\n" +
                "    }\n" +
                "}\n\n" +
                "class SomeDependency {\n" +
                "    public void process() {}\n" +
                "}";

        Path javaFile = tempDir.resolve("FieldInjectionService.java");

        // Format and check
        FormatterResult result = formatter.format(javaFile, javaCode);

        // Should detect the injection style issue
        assertFalse(result.getErrors().isEmpty(), "Should detect errors");
        assertTrue(result.getErrors().stream()
                        .anyMatch(error -> error.getMessage().contains("Field injection detected")),
                "Should detect field injection issue");
    }

    @Test
    @DisplayName("Should detect Spring component naming issues")
    public void testComponentNamingDetection() {
        // Create incorrectly named Spring components
        String javaCode = "package com.example;\n\n" +
                "import org.springframework.stereotype.Service;\n" +
                "import org.springframework.stereotype.Repository;\n" +
                "import org.springframework.stereotype.Controller;\n\n" +
                "@Service\n" +
                "public class BadlyNamed {\n" + // Missing 'Service' suffix
                "    public void doSomething() {}\n" +
                "}\n\n" +
                "@Repository\n" +
                "class DataAccess {\n" + // Missing 'Repository' suffix
                "    public void findData() {}\n" +
                "}\n\n" +
                "@Controller\n" +
                "class UserApi {\n" + // Missing 'Controller' suffix
                "    public void handleRequest() {}\n" +
                "}";

        Path javaFile = tempDir.resolve("BadlyNamed.java");

        // Format and check
        FormatterResult result = formatter.format(javaFile, javaCode);

        // Should detect naming issues
        assertFalse(result.getErrors().isEmpty(), "Should detect errors");
        assertTrue(result.getErrors().stream()
                        .anyMatch(error -> error.getMessage().contains("class name should end with")),
                "Should detect component naming issues");
    }

    @Test
    @DisplayName("Should apply method extraction refactoring")
    public void testMethodExtractionRefactoring() {
        // Create a class with a method that needs refactoring
        String javaCode = "package com.example;\n\n" +
                "public class RefactoringCandidate {\n" +
                "    public void longMethod() {\n" +
                "        // This method will be split\n" +
                "        System.out.println(\"Part 1 - Line 1\");\n" +
                "        System.out.println(\"Part 1 - Line 2\");\n" +
                "        System.out.println(\"Part 1 - Line 3\");\n" +
                "        System.out.println(\"Part 1 - Line 4\");\n" +
                "        System.out.println(\"Part 1 - Line 5\");\n" +
                "        System.out.println(\"Part 1 - Line 6\");\n" +
                "        System.out.println(\"Part 1 - Line 7\");\n" +
                "        System.out.println(\"Part 1 - Line 8\");\n" +
                "        System.out.println(\"Part 1 - Line 9\");\n" +
                "        System.out.println(\"Part 1 - Line 10\");\n" +
                "        // Second part that should be extracted\n" +
                "        System.out.println(\"Part 2 - Line 1\");\n" +
                "        System.out.println(\"Part 2 - Line 2\");\n" +
                "        System.out.println(\"Part 2 - Line 3\");\n" +
                "        System.out.println(\"Part 2 - Line 4\");\n" +
                "        System.out.println(\"Part 2 - Line 5\");\n" +
                "        System.out.println(\"Part 2 - Line 6\");\n" +
                "        System.out.println(\"Part 2 - Line 7\");\n" +
                "        System.out.println(\"Part 2 - Line 8\");\n" +
                "        System.out.println(\"Part 2 - Line 9\");\n" +
                "        System.out.println(\"Part 2 - Line 10\");\n" +
                "    }\n" +
                "}";

        Path javaFile = tempDir.resolve("RefactoringCandidate.java");

        // Format and check
        FormatterResult result = formatter.format(javaFile, javaCode);

        // Should apply method extraction refactoring
        assertTrue(result.isSuccessful(), "Formatting should be successful");
        assertNotNull(result.getFormattedCode(), "Formatted code should not be null");

        // Check for the refactoring in the result
        boolean hasMethodExtractionRefactoring = result.getAppliedRefactorings().stream()
                .anyMatch(r -> r.getType().equals("METHOD_EXTRACTION"));

        assertTrue(hasMethodExtractionRefactoring, "Should apply method extraction refactoring");

        // Verify the code has been split
        assertTrue(result.getFormattedCode().contains("longMethodHelper"),
                "Refactored code should contain a helper method");
    }

    @Test
    @DisplayName("Should detect and fix autowired field visibility")
    public void testAutowiredFieldVisibilityFix() {
        // Create a Spring component with non-private autowired fields
        String javaCode = "package com.example;\n\n" +
                "import org.springframework.beans.factory.annotation.Autowired;\n" +
                "import org.springframework.stereotype.Service;\n\n" +
                "@Service\n" +
                "public class VisibilityService {\n" +
                "    @Autowired\n" +
                "    public SomeDependency dependency1;\n" + // Should be private
                "    \n" +
                "    @Autowired\n" +
                "    protected OtherDependency dependency2;\n" + // Should be private
                "    \n" +
                "    public void doSomething() {\n" +
                "        dependency1.process();\n" +
                "        dependency2.process();\n" +
                "    }\n" +
                "}\n\n" +
                "class SomeDependency {\n" +
                "    public void process() {}\n" +
                "}\n\n" +
                "class OtherDependency {\n" +
                "    public void process() {}\n" +
                "}";

        Path javaFile = tempDir.resolve("VisibilityService.java");

        // Format and check
        FormatterResult result = formatter.format(javaFile, javaCode);

        // Should detect visibility issues
        assertFalse(result.getErrors().isEmpty(), "Should detect errors");
        assertTrue(result.getErrors().stream()
                        .anyMatch(error -> error.getMessage().contains("Autowired field should be private")),
                "Should detect autowired field visibility issues");

        // Should fix the visibility
        boolean hasAutowiringFix = result.getAppliedRefactorings().stream()
                .anyMatch(r -> r.getType().equals("SPRING_AUTOWIRING_FIX"));

        assertTrue(hasAutowiringFix, "Should apply autowiring fix");

        // Verify private fields in formatted code
        assertTrue(result.getFormattedCode().contains("private SomeDependency"),
                "Should fix visibility to private");
        assertTrue(result.getFormattedCode().contains("private OtherDependency"),
                "Should fix visibility to private");
    }
}