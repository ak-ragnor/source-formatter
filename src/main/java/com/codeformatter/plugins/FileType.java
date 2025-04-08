package com.codeformatter.plugins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 *  enum representing supported file types with improved detection capabilities.
 */
public enum FileType {
    JAVA("java", false),
    JAVASCRIPT("js", true),
    JSX("jsx", true),
    TYPESCRIPT("ts", true),
    TSX("tsx", true),
    UNKNOWN("", false);

    private final String extension;
    private final boolean isJavaScriptFamily;

    // Regular expressions for content-based detection
    private static final Pattern REACT_PATTERN = Pattern.compile(
            "(?:import\\s+React|from\\s+['\"]react['\"]|extends\\s+Component|" +
                    "<\\w+\\s*[/>]|function\\s+\\w+\\s*\\(\\s*\\)\\s*\\{\\s*return\\s*<)",
            Pattern.DOTALL);

    private static final Pattern TYPESCRIPT_PATTERN = Pattern.compile(
            "(?:interface\\s+\\w+|type\\s+\\w+\\s*=|:\\s*\\w+\\[\\])",
            Pattern.DOTALL);

    private static final Pattern JSX_PATTERN = Pattern.compile(
            "(<[A-Z][\\w.]*\\s*[^>]*>|<[A-Z][\\w.]*\\s*/>)",
            Pattern.DOTALL);

    private static final Pattern JAVA_SPRING_PATTERN = Pattern.compile(
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
     * Detects file type based on extension and content analysis.
     *
     * @param filePath The path to the file
     * @return The detected FileType
     */
    public static FileType detect(Path filePath) {
        // First try by extension
        FileType typeByExtension = detectByExtension(filePath);
        if (typeByExtension != UNKNOWN) {
            return typeByExtension;
        }

        // If extension detection failed, try content-based detection
        return detectByContent(filePath);
    }

    /**
     * Detect file type by file extension.
     */
    private static FileType detectByExtension(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();

        // Check if file has an extension
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

    /**
     * Detect file type by analyzing file content.
     */
    private static FileType detectByContent(Path filePath) {
        try {
            // Read first 20 lines or whole file if smaller
            List<String> lines = readFirstLines(filePath, 20);
            if (lines.isEmpty()) {
                return UNKNOWN;
            }

            String content = String.join("\n", lines);

            // Check for shebang line for script files
            if (content.startsWith("#!/usr/bin/env node") ||
                    content.startsWith("#!/bin/node") ||
                    content.startsWith("#!/usr/bin/node")) {
                return JAVASCRIPT;
            }

            // Check for Java patterns
            if (content.contains("public class") ||
                    content.contains("package ") ||
                    JAVA_SPRING_PATTERN.matcher(content).find()) {
                return JAVA;
            }

            // Check for TypeScript first (more specific than JavaScript)
            if (TYPESCRIPT_PATTERN.matcher(content).find()) {
                // Further check if it's TSX
                if (JSX_PATTERN.matcher(content).find()) {
                    return TSX;
                }
                return TYPESCRIPT;
            }

            // Check for React/JSX patterns
            if (REACT_PATTERN.matcher(content).find()) {
                // Further check if it's JSX
                if (JSX_PATTERN.matcher(content).find()) {
                    return JSX;
                }
                return JAVASCRIPT;
            }

            // Check for general JavaScript patterns
            if (content.contains("function ") ||
                    content.contains("const ") ||
                    content.contains("let ") ||
                    content.contains("var ") ||
                    content.contains("import ") ||
                    content.contains("export ")) {
                return JAVASCRIPT;
            }

            return UNKNOWN;
        } catch (IOException e) {
            // If we can't read the file, return UNKNOWN
            return UNKNOWN;
        }
    }

    /**
     * Read first N lines from a file.
     */
    private static List<String> readFirstLines(Path filePath, int maxLines) throws IOException {
        try {
            return Files.lines(filePath, StandardCharsets.UTF_8)
                    .limit(maxLines)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            // Try with different encoding if UTF-8 fails
            try {
                return Files.lines(filePath)
                        .limit(maxLines)
                        .collect(Collectors.toList());
            } catch (IOException e2) {
                // If all reading attempts fail, return empty list
                return new ArrayList<>();
            }
        }
    }

    /**
     * Get a human-readable description of the file type.
     */
    public String getDescription() {
        switch (this) {
            case JAVA:
                return "Java source file";
            case JAVASCRIPT:
                return "JavaScript source file";
            case JSX:
                return "React JSX source file";
            case TYPESCRIPT:
                return "TypeScript source file";
            case TSX:
                return "React TypeScript (TSX) source file";
            default:
                return "Unknown file type";
        }
    }
}