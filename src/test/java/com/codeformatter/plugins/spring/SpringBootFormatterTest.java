package com.codeformatter.plugins.spring;

import static org.junit.jupiter.api.Assertions.*;

import com.codeformatter.api.FormatterResult;
import com.codeformatter.config.FormatterConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for SpringBootFormatter functionality. */
public class SpringBootFormatterTest {

  private SpringBootFormatter formatter;
  private FormatterConfig config;

  @TempDir private Path tempDir;

  @BeforeEach
  public void setup() {
    Map<String, Object> generalConfig = new HashMap<>();
    generalConfig.put("indentSize", 4);
    generalConfig.put("lineLength", 100);

    Map<String, Object> springConfig = new HashMap<>();
    springConfig.put("maxMethodLines", 20);
    springConfig.put("maxMethodComplexity", 5);
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
    String javaCode =
        "package com.example;\n\n"
            + "public class LongMethodExample {\n"
            + "    public void longMethod() {\n"
            + "        // This method has too many lines\n"
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
            + "}";

    Path javaFile = tempDir.resolve("LongMethodExample.java");

    FormatterResult result = formatter.format(javaFile, javaCode);

    assertFalse(result.getErrors().isEmpty(), "Should detect errors");
    assertTrue(
        result.getErrors().stream().anyMatch(error -> error.getMessage().contains("too long")),
        "Should detect method length issue");
  }

  @Test
  @DisplayName("Should detect and report method complexity issues")
  public void testMethodComplexityDetection() {
    String javaCode =
        "package com.example;\n\n"
            + "public class ComplexMethodExample {\n"
            + "    public void complexMethod(int value) {\n"
            + "        // This method has too many branches\n"
            + "        if (value < 0) {\n"
            + "            System.out.println(\"Negative\");\n"
            + "        } else if (value == 0) {\n"
            + "            System.out.println(\"Zero\");\n"
            + "        } else if (value < 10) {\n"
            + "            System.out.println(\"Small\");\n"
            + "        } else if (value < 100) {\n"
            + "            System.out.println(\"Medium\");\n"
            + "        } else {\n"
            + "            System.out.println(\"Large\");\n"
            + "        }\n"
            + "        \n"
            + "        for (int i = 0; i < value; i++) {\n"
            + "            if (i % 2 == 0) {\n"
            + "                System.out.println(\"Even\");\n"
            + "            } else {\n"
            + "                System.out.println(\"Odd\");\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "}";

    Path javaFile = tempDir.resolve("ComplexMethodExample.java");

    FormatterResult result = formatter.format(javaFile, javaCode);

    assertFalse(result.getErrors().isEmpty(), "Should detect errors");
    assertTrue(
        result.getErrors().stream().anyMatch(error -> error.getMessage().contains("too complex")),
        "Should detect method complexity issue");
  }

  @Test
  @DisplayName("Should detect Spring dependency injection style issues")
  public void testDependencyInjectionDetection() {
    String javaCode =
        "package com.example;\n\n"
            + "import org.springframework.beans.factory.annotation.Autowired;\n"
            + "import org.springframework.stereotype.Service;\n\n"
            + "@Service\n"
            + "public class FieldInjectionService {\n"
            + "    @Autowired\n"
            + // Field injection when constructor injection is preferred
            "    private SomeDependency dependency;\n"
            + "    \n"
            + "    public void doSomething() {\n"
            + "        dependency.process();\n"
            + "    }\n"
            + "}\n\n"
            + "class SomeDependency {\n"
            + "    public void process() {}\n"
            + "}";

    Path javaFile = tempDir.resolve("FieldInjectionService.java");

    FormatterResult result = formatter.format(javaFile, javaCode);

    assertFalse(result.getErrors().isEmpty(), "Should detect errors");
    assertTrue(
        result.getErrors().stream()
            .anyMatch(error -> error.getMessage().contains("Field injection detected")),
        "Should detect field injection issue");
  }

  @Test
  @DisplayName("Should detect Spring component naming issues")
  public void testComponentNamingDetection() {
    String javaCode =
        "package com.example;\n\n"
            + "import org.springframework.stereotype.Service;\n"
            + "import org.springframework.stereotype.Repository;\n"
            + "import org.springframework.stereotype.Controller;\n\n"
            + "@Service\n"
            + "public class BadlyNamed {\n"
            + "    public void doSomething() {}\n"
            + "}\n\n"
            + "@Repository\n"
            + "class DataAccess {\n"
            + "    public void findData() {}\n"
            + "}\n\n"
            + "@Controller\n"
            + "class UserApi {\n"
            + "    public void handleRequest() {}\n"
            + "}";

    Path javaFile = tempDir.resolve("BadlyNamed.java");

    FormatterResult result = formatter.format(javaFile, javaCode);

    assertFalse(result.getErrors().isEmpty(), "Should detect errors");
    assertTrue(
        result.getErrors().stream()
            .anyMatch(error -> error.getMessage().contains("class name should end with")),
        "Should detect component naming issues");
  }

  @Test
  @DisplayName("Should convert field injection to constructor injection")
  public void testAutowiredFieldVisibilityFix() throws Exception {
    String javaCode =
        "package com.example;\n\n"
            + "import org.springframework.beans.factory.annotation.Autowired;\n"
            + "import org.springframework.stereotype.Service;\n\n"
            + "@Service\n"
            + "public class VisibilityService {\n"
            + "    @Autowired\n"
            + "    public SomeDependency dependency1;\n"
            + "    \n"
            + "    @Autowired\n"
            + "    protected OtherDependency dependency2;\n"
            + "    \n"
            + "    public void doSomething() {\n"
            + "        dependency1.process();\n"
            + "        dependency2.process();\n"
            + "    }\n"
            + "}\n\n"
            + "class SomeDependency {\n"
            + "    public void process() {}\n"
            + "}\n\n"
            + "class OtherDependency {\n"
            + "    public void process() {}\n"
            + "}";

    Path javaFile = tempDir.resolve("VisibilityService.java");
    Files.writeString(javaFile, javaCode);

    FormatterResult result = formatter.format(javaFile, javaCode);

    assertFalse(result.getErrors().isEmpty(), "Should detect errors");

    boolean foundInjectionError =
        result.getErrors().stream()
            .anyMatch(error -> error.getMessage().contains("Field injection detected"));

    assertTrue(foundInjectionError, "Should detect field injection issue");

    String formattedCode = result.getFormattedCode();

    assertTrue(
        formattedCode.contains("@Autowired")
            && formattedCode.contains("public VisibilityService(")
            && formattedCode.contains("SomeDependency dependency1")
            && formattedCode.contains("OtherDependency dependency2")
            && formattedCode.contains("this.dependency1 = dependency1")
            && formattedCode.contains("this.dependency2 = dependency2"),
        "Should convert to constructor injection");

    boolean hasAutowiringFix =
        result.getAppliedRefactorings().stream().anyMatch(r -> r.getType().contains("SPRING"));

    assertTrue(hasAutowiringFix, "Should apply a Spring-related refactoring");
  }
}
