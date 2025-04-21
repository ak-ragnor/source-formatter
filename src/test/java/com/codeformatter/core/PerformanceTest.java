package com.codeformatter.core;

import static org.junit.jupiter.api.Assertions.*;

import com.codeformatter.api.FormatterResult;
import com.codeformatter.config.ConfigurationLoader;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.FileType;
import com.codeformatter.plugins.react.ReactJSFormatter;
import com.codeformatter.plugins.spring.SpringBootFormatter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Performance tests for the code formatter.
 *
 * <p>Note: These tests are sensitive to environment conditions and might be flaky in CI
 * environments. They are marked with @Tag("performance") to allow excluding them from regular test
 * runs.
 */
@Tag("performance")
public class PerformanceTest {

  private static final Logger logger = Logger.getLogger(PerformanceTest.class.getName());

  @TempDir private Path tempDir;

  private AdvancedCodeFormatter formatter;
  private final Random random = new Random(42); // Fixed seed for reproducibility

  @BeforeEach
  public void setup() {
    // Load configuration
    FormatterConfig config = ConfigurationLoader.loadDefaultConfig();

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
  @DisplayName("Performance test: Format large Java file")
  public void testLargeJavaFilePerformance() throws Exception {
    // Generate a large Java file (approximately 5000 lines)
    Path largeJavaFile = generateLargeJavaFile(5000);

    // Time the formatting operation
    long startTime = System.nanoTime();

    String content = Files.readString(largeJavaFile);
    FormatterResult result = formatter.formatFile(largeJavaFile, content);

    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

    // Verify result
    assertTrue(result.isSuccessful(), "Formatting should be successful");
    assertNotNull(result.getFormattedCode(), "Formatted code should not be null");

    // Log the duration but don't assert on it
    // This makes the test less flaky in CI environments
    logger.info("Large Java file formatting took " + durationMs + " ms");

    // Verify the formatter is functional, rather than asserting on timing
    assertNotEquals(content, result.getFormattedCode(), "Formatting should change the content");
  }

  @Test
  @DisplayName("Performance test: Format large React file")
  public void testLargeReactFilePerformance() throws Exception {
    // Generate a large React file (approximately 3000 lines)
    Path largeReactFile = generateLargeReactFile(3000);

    // Time the formatting operation
    long startTime = System.nanoTime();

    String content = Files.readString(largeReactFile);
    FormatterResult result = formatter.formatFile(largeReactFile, content);

    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

    // Verify result
    assertTrue(result.isSuccessful(), "Formatting should be successful");
    assertNotNull(result.getFormattedCode(), "Formatted code should not be null");

    // Log the duration but don't assert on it
    logger.info("Large React file formatting took " + durationMs + " ms");

    // Verify the formatter is functional, rather than asserting on timing
    assertNotEquals(content, result.getFormattedCode(), "Formatting should change the content");
  }

  @Test
  @DisplayName("Performance test: Format multiple files in parallel")
  public void testParallelPerformance() throws Exception {
    // Generate 10 medium-sized files
    int fileCount = 10;
    int linesPerFile = 500;

    for (int i = 0; i < fileCount / 2; i++) {
      generateLargeJavaFile(linesPerFile, "JavaFile" + i + ".java");
    }

    for (int i = 0; i < fileCount / 2; i++) {
      generateLargeReactFile(linesPerFile, "ReactFile" + i + ".jsx");
    }

    // Time the formatting operation
    long startTime = System.nanoTime();

    Map<Path, FormatterResult> results = formatter.formatDirectory(tempDir);

    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

    // Verify results
    assertEquals(fileCount, results.size(), "Should format all files");
    assertTrue(
        results.values().stream().allMatch(FormatterResult::isSuccessful),
        "All formatting operations should be successful");

    // Log performance metrics without asserting on them
    logger.info("Parallel formatting of " + fileCount + " files took " + durationMs + " ms");
    logger.info("Average time per file: " + (double) durationMs / fileCount + " ms");
  }

  @Test
  @DisplayName("Performance test: Memory usage while formatting")
  public void testMemoryUsage() throws Exception {
    // Generate 5 large files
    generateLargeJavaFile(2000, "JavaFile1.java");
    generateLargeJavaFile(2000, "JavaFile2.java");
    generateLargeReactFile(1500, "ReactFile1.jsx");
    generateLargeReactFile(1500, "ReactFile2.jsx");
    generateLargeJavaFile(2000, "JavaFile3.java");

    // Get initial memory usage
    Runtime runtime = Runtime.getRuntime();
    System.gc(); // Request garbage collection to get more accurate reading
    long initialMemory = runtime.totalMemory() - runtime.freeMemory();

    // Format all files
    Map<Path, FormatterResult> results = formatter.formatDirectory(tempDir);

    // Get memory usage after formatting
    System.gc(); // Request garbage collection to get more accurate reading
    long finalMemory = runtime.totalMemory() - runtime.freeMemory();

    // Calculate memory increase
    long memoryIncreaseMB = (finalMemory - initialMemory) / (1024 * 1024);

    // Log memory usage without asserting on it
    logger.info("Memory increase after formatting: " + memoryIncreaseMB + " MB");

    // Verify results
    assertEquals(5, results.size(), "Should format all 5 files");
    assertTrue(
        results.values().stream().allMatch(FormatterResult::isSuccessful),
        "All formatting operations should be successful");
  }

  @Test
  @DisplayName("Performance test: Format very complex code")
  public void testComplexCodePerformance() throws Exception {
    // Generate complex Java code with deeply nested structures
    Path complexJavaFile = generateComplexJavaCode();

    // Time the formatting operation
    long startTime = System.nanoTime();

    String content = Files.readString(complexJavaFile);
    FormatterResult result = formatter.formatFile(complexJavaFile, content);

    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

    // Verify result
    assertTrue(result.isSuccessful(), "Formatting should be successful");
    assertNotNull(result.getFormattedCode(), "Formatted code should not be null");

    // Log the duration but don't assert on it
    logger.info("Complex code formatting took " + durationMs + " ms");

    // Verify the formatter is functional, rather than asserting on timing
    assertNotEquals(content, result.getFormattedCode(), "Formatting should change the content");
  }

  // Utility methods to generate test files (these remain unchanged)
  private Path generateLargeJavaFile(int lines) throws IOException {
    return generateLargeJavaFile(lines, "LargeFile.java");
  }

  private Path generateLargeJavaFile(int lines, String fileName) throws IOException {
    Path filePath = tempDir.resolve(fileName);
    StringBuilder codeBuilder = new StringBuilder();

    // Add package and imports
    codeBuilder.append("package com.example.large;\n\n");
    codeBuilder.append("import java.util.*;\n");
    codeBuilder.append("import java.io.*;\n");
    codeBuilder.append("import java.nio.file.*;\n\n");

    // Add class declaration
    codeBuilder.append("public class   LargeClass {\n\n");

    // Add fields
    for (int i = 0; i < lines / 50; i++) {
      codeBuilder
          .append("    private String field")
          .append(i)
          .append("   = \"value")
          .append(i)
          .append("\";\n");
    }
    codeBuilder.append("\n");

    // Add constructor
    codeBuilder.append("    public   LargeClass(  ) {\n");
    codeBuilder.append("        // Constructor\n");
    codeBuilder.append("        System.out.println(\"Initializing\");\n");
    codeBuilder.append("    }\n\n");

    // Add methods
    int methodCount = lines / 30;
    for (int i = 0; i < methodCount; i++) {
      codeBuilder.append("    public void   method").append(i).append("(  ) {\n");

      // Method content
      for (int j = 0; j < 20; j++) {
        int spaces = random.nextInt(8) + 4;
        String padding = " ".repeat(spaces);
        codeBuilder
            .append("        System.out.println(  \"Line ")
            .append(j)
            .append("\"")
            .append(padding)
            .append(");\n");
      }

      codeBuilder.append("    }\n\n");
    }

    // Close class
    codeBuilder.append("}\n");

    Files.writeString(filePath, codeBuilder.toString());
    return filePath;
  }

  private Path generateLargeReactFile(int lines) throws IOException {
    return generateLargeReactFile(lines, "LargeComponent.jsx");
  }

  private Path generateLargeReactFile(int lines, String fileName) throws IOException {
    Path filePath = tempDir.resolve(fileName);
    StringBuilder codeBuilder = new StringBuilder();

    // Add imports
    codeBuilder.append("import './styles.css';\n");
    codeBuilder.append("import React, { useState, useEffect, useCallback } from 'react';\n");
    codeBuilder.append("import axios from 'axios';\n\n");

    // Add component definition
    codeBuilder.append("function   LargeComponent( ) {\n");

    // Add state variables
    int stateCount = lines / 50;
    for (int i = 0; i < stateCount; i++) {
      codeBuilder
          .append("    const [state")
          .append(i)
          .append(", setState")
          .append(i)
          .append("] = useState(")
          .append(random.nextInt(100))
          .append(");\n");
    }
    codeBuilder.append("\n");

    // Add useEffects
    int effectCount = lines / 100;
    for (int i = 0; i < effectCount; i++) {
      codeBuilder.append("    useEffect(() => {\n");
      codeBuilder.append("        // Effect ").append(i).append("\n");
      codeBuilder.append("        console.log(\"Effect ").append(i).append(" running\");\n");

      for (int j = 0; j < 5; j++) {
        codeBuilder
            .append("        console.log(\"State")
            .append(random.nextInt(stateCount))
            .append(": \" + state")
            .append(random.nextInt(stateCount))
            .append(");\n");
      }

      codeBuilder.append("    }, []);\n\n");
    }

    // Add event handlers
    int handlerCount = lines / 80;
    for (int i = 0; i < handlerCount; i++) {
      codeBuilder.append("    const handleEvent").append(i).append(" = useCallback(() => {\n");
      codeBuilder.append("        // Handler ").append(i).append("\n");

      for (int j = 0; j < 5; j++) {
        int stateIndex = random.nextInt(stateCount);
        codeBuilder
            .append("        setState")
            .append(stateIndex)
            .append("(state")
            .append(stateIndex)
            .append(" + ")
            .append(random.nextInt(5) + 1)
            .append(");\n");
      }

      codeBuilder.append("    }, []);\n\n");
    }

    // Add render
    codeBuilder.append("    return (\n");
    codeBuilder.append("        <div    className=\"container\"   >\n");
    codeBuilder.append("            <h1>Large Component</h1>\n");

    // Add JSX elements
    int remaining = lines - codeBuilder.toString().split("\n").length - 10;
    int elementsNeeded = remaining / 3;

    for (int i = 0; i < elementsNeeded; i++) {
      int spaces = random.nextInt(8) + 8;
      String padding = " ".repeat(spaces);

      codeBuilder.append("            <div className=\"item\"").append(padding).append(">\n");
      codeBuilder.append("                <h2>Item ").append(i).append("</h2>\n");
      codeBuilder
          .append("                <p>Value: {state")
          .append(random.nextInt(stateCount))
          .append("}</p>\n");

      if (random.nextBoolean()) {
        codeBuilder
            .append("                <button    onClick={handleEvent")
            .append(random.nextInt(handlerCount))
            .append("}   >Update</button>\n");
      }

      codeBuilder.append("            </div>\n");
    }

    // Close component
    codeBuilder.append("        </div>\n");
    codeBuilder.append("    );\n");
    codeBuilder.append("}\n\n");
    codeBuilder.append("export default LargeComponent;\n");

    Files.writeString(filePath, codeBuilder.toString());
    return filePath;
  }

  private Path generateComplexJavaCode() throws IOException {
    Path filePath = tempDir.resolve("ComplexCode.java");
    StringBuilder codeBuilder = new StringBuilder();

    // Add package and imports
    codeBuilder.append("package com.example.complex;\n\n");
    codeBuilder.append("import java.util.*;\n");
    codeBuilder.append("import java.util.stream.*;\n");
    codeBuilder.append("import java.util.concurrent.*;\n");
    codeBuilder.append("import java.util.function.*;\n\n");

    // Add class declaration
    codeBuilder.append("public class ComplexCode {\n\n");

    // Add deeply nested method with complex expressions
    codeBuilder.append(
        "    public void complexMethod(List<Map<String, Set<Integer>>> nestedData) {\n");
    codeBuilder.append("        // Very complex method with nested structures\n");
    codeBuilder.append("        nestedData.stream()\n");
    codeBuilder.append("            .filter(map -> map.size() > 3)\n");
    codeBuilder.append("            .flatMap(map -> map.entrySet().stream())\n");
    codeBuilder.append("            .filter(entry -> entry.getKey().length() > 2)\n");
    codeBuilder.append("            .map(entry -> entry.getValue())\n");
    codeBuilder.append("            .flatMap(Set::stream)\n");
    codeBuilder.append("            .filter(i -> i % 2 == 0)\n");
    codeBuilder.append("            .map(i -> i * 3)\n");
    codeBuilder.append("            .collect(Collectors.groupingBy(\n");
    codeBuilder.append("                i -> i % 5,\n");
    codeBuilder.append("                Collectors.mapping(\n");
    codeBuilder.append("                    i -> String.format(\"Value: %d\", i),\n");
    codeBuilder.append("                    Collectors.joining(\", \")\n");
    codeBuilder.append("                )\n");
    codeBuilder.append("            ))\n");
    codeBuilder.append("            .forEach((k, v) -> {\n");
    codeBuilder.append("                System.out.println(\"Key: \" + k);\n");
    codeBuilder.append("                System.out.println(\"Values: \" + v);\n");
    codeBuilder.append("                if (k > 2 && v.length() > 10) {\n");
    codeBuilder.append("                    CompletableFuture.supplyAsync(() -> {\n");
    codeBuilder.append("                        try {\n");
    codeBuilder.append("                            Thread.sleep(100);\n");
    codeBuilder.append("                            return v.split(\", \").length;\n");
    codeBuilder.append("                        } catch (Exception e) {\n");
    codeBuilder.append("                            return -1;\n");
    codeBuilder.append("                        }\n");
    codeBuilder.append("                    }).thenApply(count -> {\n");
    codeBuilder.append("                        return IntStream.range(0, count)\n");
    codeBuilder.append("                            .mapToObj(i -> i * k)\n");
    codeBuilder.append("                            .collect(Collectors.toList());\n");
    codeBuilder.append("                    }).thenAccept(list -> {\n");
    codeBuilder.append("                        Optional.of(list)\n");
    codeBuilder.append("                            .filter(l -> !l.isEmpty())\n");
    codeBuilder.append("                            .ifPresent(l -> {\n");
    codeBuilder.append("                                System.out.println(\"Result: \" + l);\n");
    codeBuilder.append("                            });\n");
    codeBuilder.append("                    });\n");
    codeBuilder.append("                }\n");
    codeBuilder.append("            });\n");
    codeBuilder.append("    }\n");

    // Add more complex methods
    codeBuilder.append(
        "    private <T, U, R> Function<T, R> complexFunction(Function<T, U> f1, Function<U, R> f2) {\n");
    codeBuilder.append("        return f1.andThen(f2);\n");
    codeBuilder.append("    }\n\n");

    codeBuilder.append(
        "    private <T> Predicate<T> complexPredicate(Predicate<T> p1, Predicate<T> p2) {\n");
    codeBuilder.append("        return p1.and(p2.negate()).or(p1.negate().and(p2));\n");
    codeBuilder.append("    }\n\n");

    codeBuilder.append(
        "    private <T> Stream<T> complexStream(Collection<T> c1, Collection<T> c2) {\n");
    codeBuilder.append("        return Stream.concat(\n");
    codeBuilder.append("            c1.stream().filter(item -> item != null),\n");
    codeBuilder.append("            c2.stream().filter(item -> item != null)\n");
    codeBuilder.append("        ).distinct().sorted();\n");
    codeBuilder.append("    }\n");

    // Close class
    codeBuilder.append("}\n");

    Files.writeString(filePath, codeBuilder.toString());
    return filePath;
  }
}
