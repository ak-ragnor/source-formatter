package com.codeformatter.api;

import java.nio.file.Path;
import java.util.Map;

/**
 * The main formatter interface that all implementations must provide.
 */
public interface CodeFormatter {
    FormatterResult formatFile(Path filePath, String sourceCode);
    Map<Path, FormatterResult> formatDirectory(Path directory);
}