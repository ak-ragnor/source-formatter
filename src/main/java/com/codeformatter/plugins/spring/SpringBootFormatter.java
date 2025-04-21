package com.codeformatter.plugins.spring;

import com.codeformatter.api.FormatterPlugin;
import com.codeformatter.api.FormatterResult;
import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.analyzers.*;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.Printer;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration.ConfigOption;
import com.github.javaparser.printer.configuration.Indentation;
import com.github.javaparser.printer.configuration.Indentation.IndentType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Spring Boot code formatter plugin. This plugin uses JavaParser to analyze and refactor Java code
 * with awareness of Spring Boot specific patterns, enforcing a consistent code style.
 */
public class SpringBootFormatter implements FormatterPlugin, AutoCloseable {

  private static final Logger logger = Logger.getLogger(SpringBootFormatter.class.getName());

  private FormatterConfig config;
  private List<CodeAnalyzer> analyzers;
  private DefaultPrinterConfiguration printerConfig;
  private Printer printer;

  private final Map<String, CompilationUnit> astCache =
      new LinkedHashMap<String, CompilationUnit>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CompilationUnit> eldest) {
          return size() > 100;
        }
      };

  private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
  private final ReentrantReadWriteLock.ReadLock readLock = cacheLock.readLock();
  private final ReentrantReadWriteLock.WriteLock writeLock = cacheLock.writeLock();

  @Override
  public void initialize(FormatterConfig config) {
    this.config = config;

    // Create and configure pretty printer
    printerConfig = new DefaultPrinterConfiguration();
    // Indentation settings
    printerConfig.addOption(
        new DefaultConfigurationOption(
            ConfigOption.INDENTATION,
            new Indentation(
                config.getGeneralConfig("useTabs", false) ? IndentType.TABS : IndentType.SPACES,
                config.getGeneralConfig("indentSize", 4))));
    // Print comments
    printerConfig.addOption(new DefaultConfigurationOption(ConfigOption.PRINT_COMMENTS, true));
    // End-of-line character
    printerConfig.addOption(
        new DefaultConfigurationOption(ConfigOption.END_OF_LINE_CHARACTER, "\n"));
    // Order imports backup
    printerConfig.addOption(new DefaultConfigurationOption(ConfigOption.ORDER_IMPORTS, true));

    // Build printer
    printer = new DefaultPrettyPrinter(printerConfig);

    // Initialize all analyzers
    analyzers = new ArrayList<>();
    analyzers.add(new MethodSizeAnalyzer(config));
    analyzers.add(new ImportOrganizer(config));
    analyzers.add(new DesignPatternAnalyzer(config));
    analyzers.add(new SpringComponentAnalyzer(config));
    analyzers.add(new CodeStyleAnalyzer(config));
    analyzers.add(new JavaConventionAnalyzer(config));

    logger.info("Initialized SpringBootFormatter with " + analyzers.size() + " analyzers");
  }

  @Override
  public FormatterResult format(Path filePath, String sourceCode) {
    if (sourceCode == null || sourceCode.trim().isEmpty()) {
      return FormatterResult.builder().successful(true).formattedCode(sourceCode).build();
    }

    logger.fine("Formatting file: " + filePath);

    String cacheKey = filePath.toString() + ":" + sourceCode.hashCode();
    CompilationUnit cu = null;

    readLock.lock();
    try {
      cu = astCache.get(cacheKey);
    } finally {
      readLock.unlock();
    }

    if (cu == null) {
      JavaParser parser = new JavaParser();
      ParseResult<CompilationUnit> parseResult = parser.parse(sourceCode);

      if (!parseResult.isSuccessful()) {
        return handleParseError(parseResult);
      }

      cu = parseResult.getResult().get();

      writeLock.lock();
      try {
        astCache.put(cacheKey, cu);
      } finally {
        writeLock.unlock();
      }

      logger.fine("Parsed and cached AST for: " + filePath);
    }

    CompilationUnit workingCopy = cu.clone();

    List<FormatterError> errors = new ArrayList<>();
    List<Refactoring> appliedRefactorings = new ArrayList<>();

    for (CodeAnalyzer analyzer : analyzers) {
      try {
        AnalyzerResult analyzerResult = analyzer.analyze(workingCopy);
        errors.addAll(analyzerResult.getErrors());

        if (analyzer.canAutoFix()) {
          RefactoringResult refactoringResult = analyzer.applyRefactoring(workingCopy);
          appliedRefactorings.addAll(refactoringResult.getAppliedRefactorings());
          errors.addAll(refactoringResult.getErrors());
        }
      } catch (Exception e) {
        logger.log(
            Level.WARNING, "Error during analysis with " + analyzer.getClass().getSimpleName(), e);
        errors.add(
            new FormatterError(
                Severity.WARNING,
                "Exception in " + analyzer.getClass().getSimpleName() + ": " + e.getMessage(),
                1,
                1,
                "This is likely a bug in the formatter"));
      }
    }

    // Use DefaultPrettyPrinter instead of deprecated PrettyPrinter
    String formattedCode = printer.print(workingCopy);

    boolean codeChanged = !sourceCode.equals(formattedCode);
    if (codeChanged) {
      appliedRefactorings.add(
          new Refactoring(
              "JAVA_FORMATTING",
              1,
              sourceCode.split("\n").length,
              "Applied standard Java formatting"));
      logger.fine("Code formatting changed the file: " + filePath);
    } else {
      logger.fine("Code formatting did not change the file: " + filePath);
    }

    boolean successful =
        errors.stream()
            .noneMatch(e -> e.getSeverity() == Severity.FATAL || e.getSeverity() == Severity.ERROR);

    return FormatterResult.builder()
        .successful(successful)
        .formattedCode(formattedCode)
        .errors(errors)
        .appliedRefactorings(appliedRefactorings)
        .build();
  }

  private FormatterResult handleParseError(ParseResult<CompilationUnit> parseResult) {
    String errorMessage =
        parseResult.getProblems().isEmpty()
            ? "Unknown error"
            : parseResult.getProblems().get(0).getMessage();

    FormatterError error =
        new FormatterError(
            Severity.FATAL,
            "Failed to parse Java source code: " + errorMessage,
            1,
            1,
            "Check for syntax errors in the source code");

    return FormatterResult.builder().successful(false).formattedCode(null).addError(error).build();
  }

  @Override
  public void close() {
    writeLock.lock();
    try {
      astCache.clear();
      logger.fine("Cleared AST cache");
    } finally {
      writeLock.unlock();
    }
  }
}
