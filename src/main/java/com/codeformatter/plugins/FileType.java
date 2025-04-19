package com.codeformatter.plugins;

import com.codeformatter.util.LoggerUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Enum representing supported file types with improved detection capabilities and caching. */
public enum FileType {
  JAVA("java", false),
  JAVASCRIPT("js", true),
  JSX("jsx", true),
  TYPESCRIPT("ts", true),
  TSX("tsx", true),
  UNKNOWN("", false);

  private static final Logger logger = LoggerUtil.getLogger(FileType.class);
  private final String extension;
  private final boolean isJavaScriptFamily;

  private static final Map<Path, FileType> typeCache = new ConcurrentHashMap<>();
  private static final int MAX_CACHE_SIZE = 10000;

  private static final Pattern REACT_PATTERN =
      Pattern.compile(
          "(?:import\\s+React|from\\s+['\"]react['\"]|extends\\s+Component|"
              + "<\\w+\\s*[/>]|function\\s+\\w+\\s*\\(\\s*\\)\\s*\\{\\s*return\\s*<)",
          Pattern.DOTALL);

  private static final Pattern TYPESCRIPT_PATTERN =
      Pattern.compile("(?:interface\\s+\\w+|type\\s+\\w+\\s*=|:\\s*\\w+\\[\\])", Pattern.DOTALL);

  private static final Pattern JSX_PATTERN =
      Pattern.compile("(<[A-Z][\\w.]*\\s*[^>]*>|<[A-Z][\\w.]*\\s*/>)", Pattern.DOTALL);

  private static final Pattern JAVA_SPRING_PATTERN =
      Pattern.compile(
          "(?:@Controller|@Service|@Repository|@Component|@RestController|@SpringBootApplication)",
          Pattern.DOTALL);

  FileType(String extension, boolean isJavaScriptFamily) {
    this.extension = extension;
    this.isJavaScriptFamily = isJavaScriptFamily;
  }

  public String getExtension() {
    return extension;
  }

  public boolean isJavaScriptFamily() {
    return isJavaScriptFamily;
  }

  /**
   * Detects file type based on extension and content analysis. Uses caching for better performance.
   *
   * @param filePath The path to the file
   * @return The detected FileType
   */
  public static FileType detect(Path filePath) {

    FileType cachedType = typeCache.get(filePath);
    if (cachedType != null) {
      return cachedType;
    }

    if (typeCache.size() > MAX_CACHE_SIZE) {
      typeCache.clear();
      logger.fine("Cleared file type detection cache");
    }

    FileType typeByExtension = detectByExtension(filePath);
    if (typeByExtension != UNKNOWN) {
      typeCache.put(filePath, typeByExtension);
      return typeByExtension;
    }

    FileType detectedType = detectByContent(filePath);
    typeCache.put(filePath, detectedType);
    return detectedType;
  }

  /** Detect file type by file extension. */
  private static FileType detectByExtension(Path filePath) {
    String fileName = filePath.getFileName().toString().toLowerCase();

    if (!fileName.contains(".")) {
      return UNKNOWN;
    }

    String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();

    return switch (extension) {
      case "java" -> JAVA;
      case "js" -> JAVASCRIPT;
      case "jsx" -> JSX;
      case "ts" -> TYPESCRIPT;
      case "tsx" -> TSX;
      default -> UNKNOWN;
    };
  }

  /** Detect file type by analyzing file content. Optimized to only read as much as needed. */
  private static FileType detectByContent(Path filePath) {
    try {

      List<String> lines = readFirstLines(filePath, 20);
      if (lines.isEmpty()) {
        return UNKNOWN;
      }

      String content = String.join("\n", lines);

      if (content.startsWith("#!/usr/bin/env node")
          || content.startsWith("#!/bin/node")
          || content.startsWith("#!/usr/bin/node")) {
        return JAVASCRIPT;
      }

      if (content.contains("public class")
          || content.contains("package ")
          || content.contains("import java.")) {
        return JAVA;
      }

      if (JAVA_SPRING_PATTERN.matcher(content).find()) {
        return JAVA;
      }

      if (TYPESCRIPT_PATTERN.matcher(content).find()) {

        if (JSX_PATTERN.matcher(content).find()) {
          return TSX;
        }
        return TYPESCRIPT;
      }

      if (REACT_PATTERN.matcher(content).find()) {

        if (JSX_PATTERN.matcher(content).find()) {
          return JSX;
        }
        return JAVASCRIPT;
      }

      if (content.contains("function ")
          || content.contains("const ")
          || content.contains("let ")
          || content.contains("var ")
          || content.contains("import ")
          || content.contains("export ")) {
        return JAVASCRIPT;
      }

      return UNKNOWN;
    } catch (IOException e) {
      logger.log(Level.FINE, "Error reading file for type detection: " + filePath, e);

      return UNKNOWN;
    }
  }

  /**
   * Read first N lines from a file or up to 4KB, whichever comes first. Optimized for performance.
   */
  private static List<String> readFirstLines(Path filePath, int maxLines) throws IOException {
    List<String> lines = new ArrayList<>();
    try {

      byte[] bytes = Files.readAllBytes(filePath);

      int bytesToRead = Math.min(bytes.length, 4096);
      String content = new String(bytes, 0, bytesToRead, StandardCharsets.UTF_8);

      String[] contentLines = content.split("\n", maxLines + 1);
      for (int i = 0; i < contentLines.length && i < maxLines; i++) {
        lines.add(contentLines[i]);
      }

      return lines;
    } catch (IOException e) {

      try {
        return Files.lines(filePath)
            .limit(maxLines)
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
      } catch (IOException e2) {

        logger.log(Level.FINE, "Could not read file: " + filePath, e2);
        return new ArrayList<>();
      }
    }
  }

  /** Clear the file type detection cache. */
  public static void clearCache() {
    typeCache.clear();
    logger.fine("File type detection cache cleared");
  }

  /** Get the current cache size. */
  public static int getCacheSize() {
    return typeCache.size();
  }

  /** Get a human-readable description of the file type. */
  public String getDescription() {
    return switch (this) {
      case JAVA -> "Java source file";
      case JAVASCRIPT -> "JavaScript source file";
      case JSX -> "React JSX source file";
      case TYPESCRIPT -> "TypeScript source file";
      case TSX -> "React TypeScript (TSX) source file";
      case UNKNOWN -> "Unknown file type";
    };
  }
}
