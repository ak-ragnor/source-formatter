package com.codeformatter;

import static org.junit.jupiter.api.Assertions.*;

import com.codeformatter.api.FormatterResult;
import com.codeformatter.config.ConfigurationLoader;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.core.AdvancedCodeFormatter;
import com.codeformatter.plugins.FileType;
import com.codeformatter.plugins.spring.SpringBootFormatter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Integration tests for the code formatter system. */
public class IntegrationTest {

  @TempDir private Path tempDir;

  private AdvancedCodeFormatter formatter;
  private FormatterConfig config;

  @BeforeEach
  public void setup() throws IOException {

    createSampleJavaFile();
    createSampleSpringFile();

    config = ConfigurationLoader.loadDefaultConfig();

    formatter = new AdvancedCodeFormatter(config);
    formatter.registerPlugin(FileType.JAVA, new SpringBootFormatter());
  }

  @AfterEach
  public void cleanup() throws Exception {
    if (formatter != null) {
      formatter.close();
    }
  }

  private void createSampleJavaFile() throws IOException {
    Path filePath = tempDir.resolve("Sample.java");
    String content =
        """
                public class   Sample {
                    public void   sampleMethod(  ) {
                        // Method with poor formatting
                        System.out.println(  "Sample output"    );
                        int x   =   5;
                        if (x > 0)    {
                            System.out.println("Positive");
                        }
                    }
                }""";
    Files.writeString(filePath, content);
  }

  private void createSampleSpringFile() throws IOException {
    Path filePath = tempDir.resolve("SampleService.java");
    String content =
        """
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Service;
                import java.util.List;

                @Service
                public class   SampleService {
                    @Autowired
                    private SampleRepository repository;
                   \s
                    public List<String>   getSamples(  ) {
                        // Method with poor formatting
                        return repository.findAll(  );
                    }
                }

                class SampleRepository {
                    public List<String> findAll() {
                        return java.util.Collections.emptyList();
                    }
                }""";
    Files.writeString(filePath, content);
  }

  @Test
  @DisplayName("Integration test: Format entire directory")
  public void testFormatDirectory() throws Exception {

    Map<Path, FormatterResult> results = formatter.formatDirectory(tempDir);

    assertEquals(3, results.size(), "Should format 3 files");

    for (FormatterResult result : results.values()) {
      assertTrue(result.isSuccessful(), "Formatting should be successful");
      assertNotNull(result.getFormattedCode(), "Formatted code should not be null");
    }

    Path javaFile = tempDir.resolve("Sample.java");
    String formattedJava = Files.readString(javaFile);
    assertFalse(formattedJava.contains("public class   Sample"), "Extra spaces should be removed");

    Path springFile = tempDir.resolve("SampleService.java");
    String formattedSpring = Files.readString(springFile);
    assertFalse(
        formattedSpring.contains("public List<String>   getSamples(  )"),
        "Extra spaces should be removed");

    Path reactFile = tempDir.resolve("SampleComponent.jsx");
    String formattedReact = Files.readString(reactFile);
    assertFalse(
        formattedReact.contains("function   SampleComponent"), "Extra spaces should be removed");
  }

  @Test
  @DisplayName("Integration test: Spring formatting with refactorings")
  public void testSpringFormatting() throws Exception {

    Path serviceFile = tempDir.resolve("ProblemService.java");
    StringBuilder codeBuilder = new StringBuilder();
    codeBuilder.append("import org.springframework.beans.factory.annotation.Autowired;\n");
    codeBuilder.append("import org.springframework.stereotype.Service;\n\n");
    codeBuilder.append("@Service\n");
    codeBuilder.append("public class ProblemService {\n");
    codeBuilder.append("    @Autowired\n");
    codeBuilder.append("    public DataSource dataSource;\n");

    codeBuilder.append("    public void processData() {\n");
    for (int i = 0; i < 30; i++) {
      codeBuilder
          .append("        System.out.println(\"Processing data line ")
          .append(i)
          .append("\");\n");
    }
    codeBuilder.append("    }\n");
    codeBuilder.append("}\n\n");
    codeBuilder.append("class DataSource { }\n");

    Files.writeString(serviceFile, codeBuilder.toString());

    String originalCode = Files.readString(serviceFile);
    FormatterResult result = formatter.formatFile(serviceFile, originalCode);

    assertTrue(result.isSuccessful(), "Formatting should be successful");
    assertNotNull(result.getFormattedCode(), "Formatted code should not be null");
    assertNotEquals(originalCode, result.getFormattedCode(), "Code should be changed");

    assertFalse(result.getErrors().isEmpty(), "Should detect errors");

    assertFalse(result.getAppliedRefactorings().isEmpty(), "Should apply refactorings");

    String formattedCode = result.getFormattedCode();
    assertTrue(
        formattedCode.contains("private DataSource"), "Autowired field should be made private");
    assertTrue(
        formattedCode.contains("processDataHelper") || formattedCode.contains("Helper"),
        "Long method should be split");
  }

  @Test
  @DisplayName("Integration test: Check mode without modification")
  public void testCheckMode() throws Exception {

    Path nonCompliantFile = tempDir.resolve("NonCompliant.java");
    String javaCode =
        "public class   NonCompliant {\n"
            + "    public void   badMethod(  ) {\n"
            + "        System.out.println(  \"Bad formatting\"    );\n"
            + "    }\n"
            + "}";
    Files.writeString(nonCompliantFile, javaCode);

    FormatterResult result = formatter.formatFile(nonCompliantFile, javaCode);

    assertTrue(result.isSuccessful(), "Formatting should be successful");
    assertNotNull(result.getFormattedCode(), "Formatted code should not be null");
    assertNotEquals(javaCode, result.getFormattedCode(), "Formatter should detect changes needed");

    String fileContent = Files.readString(nonCompliantFile);
    assertEquals(javaCode, fileContent, "Original file should not be modified");
  }

  @Test
  @DisplayName("Integration test: Analyzing multiple files with different issues")
  public void testMultipleFileAnalysis() throws Exception {

    Map<Path, FormatterResult> results = formatter.formatDirectory(tempDir);

    long totalIssues =
        results.values().stream().flatMap(result -> result.getErrors().stream()).count();

    assertTrue(totalIssues > 0, "Should find issues in the sample files");

    Map<String, Long> issuesByFileType =
        results.entrySet().stream()
            .collect(
                Collectors.toMap(
                    e -> {
                      String fileName = e.getKey().getFileName().toString();
                      return fileName.substring(fileName.lastIndexOf('.') + 1);
                    },
                    e -> (long) e.getValue().getErrors().size(),
                    Long::sum));

    assertTrue(issuesByFileType.getOrDefault("java", 0L) > 0, "Should find issues in Java files");
  }
}
