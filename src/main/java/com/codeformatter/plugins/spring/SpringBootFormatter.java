package com.codeformatter.plugins.spring;

import com.codeformatter.api.FormatterPlugin;
import com.codeformatter.api.FormatterResult;
import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.analyzers.*;
import com.codeformatter.plugins.spring.analyzers.external.CheckstyleAnalyzer;
import com.codeformatter.plugins.spring.analyzers.external.PMDAnalyzer;
import com.codeformatter.util.LoggerUtil;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Enhanced Spring Boot code formatter with hybrid architecture and external tool auto-fix
 * integration.
 *
 * <p>Combines industry-standard tools (Checkstyle, PMD) with custom Spring-specific analyzers for
 * comprehensive code analysis and formatting. Supports automatic fixing of violations when
 * possible.
 */
public class SpringBootFormatter implements FormatterPlugin, AutoCloseable {
  private static final Logger logger = LoggerUtil.getLogger(SpringBootFormatter.class);

  private FormatterConfig config;
  private List<CodeAnalyzer> externalAnalyzers;
  private List<CodeAnalyzer> customAnalyzers;
  private DefaultPrinterConfiguration printerConfig;
  private Printer printer;

  // Enhanced caching with better eviction policy
  private final Map<String, CompilationUnit> astCache = new ConcurrentHashMap<>();
  private final Map<String, List<FormatterError>> analysisCache = new ConcurrentHashMap<>();
  private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();

  private static final int MAX_CACHE_SIZE = 500;
  private static final int CACHE_CLEANUP_THRESHOLD = 600;

  // Auto-fix configuration
  private boolean autoFixEnabled;
  private boolean autoFixOnFormat;
  private int maxFixesPerFile;
  private boolean verboseAutoFix;

  @Override
  public void initialize(FormatterConfig config) {
    this.config = config;

    // Initialize auto-fix configuration
    initializeAutoFixConfig();

    // Initialize printer configuration
    setupPrinterConfiguration();

    // Initialize analyzers with hybrid approach
    initializeAnalyzers();

    logger.info(
        "SpringBootFormatter initialized with hybrid architecture: "
            + externalAnalyzers.size()
            + " external tools + "
            + customAnalyzers.size()
            + " custom analyzers"
            + (autoFixEnabled ? " (auto-fix enabled)" : ""));
  }

  /** Initialize auto-fix configuration. */
  private void initializeAutoFixConfig() {
    autoFixEnabled = config.getGeneralConfig("autoFix.enabled", true);
    autoFixOnFormat = config.getGeneralConfig("autoFix.applyOn.format", true);
    maxFixesPerFile = config.getGeneralConfig("autoFix.maxFixesPerFile", 50);
    verboseAutoFix = config.getGeneralConfig("autoFix.verboseOutput", true);

    logger.info(
        "Auto-fix configuration: enabled="
            + autoFixEnabled
            + ", onFormat="
            + autoFixOnFormat
            + ", maxFixes="
            + maxFixesPerFile);
  }

  /** Set up printer configuration based on formatter config. */
  private void setupPrinterConfiguration() {
    printerConfig = new DefaultPrinterConfiguration();

    // Indentation settings
    printerConfig.addOption(
        new DefaultConfigurationOption(
            ConfigOption.INDENTATION,
            new Indentation(
                config.getGeneralConfig("useTabs", false) ? IndentType.TABS : IndentType.SPACES,
                config.getGeneralConfig("indentSize", 4))));

    // Other formatting options
    printerConfig.addOption(new DefaultConfigurationOption(ConfigOption.PRINT_COMMENTS, true));
    printerConfig.addOption(
        new DefaultConfigurationOption(ConfigOption.END_OF_LINE_CHARACTER, "\n"));
    printerConfig.addOption(new DefaultConfigurationOption(ConfigOption.ORDER_IMPORTS, true));

    printer = new DefaultPrettyPrinter(printerConfig);
  }

  /** Initialize analyzers using hybrid approach. */
  private void initializeAnalyzers() {
    externalAnalyzers = new ArrayList<>();
    customAnalyzers = new ArrayList<>();

    // Initialize external tool analyzers
    initializeExternalAnalyzers();

    // Initialize custom Spring-specific analyzers
    initializeCustomAnalyzers();
  }

  /** Initialize external tool analyzers (Checkstyle, PMD). */
  private void initializeExternalAnalyzers() {
    // Add Checkstyle analyzer
    try {
      CheckstyleAnalyzer checkstyle = new CheckstyleAnalyzer(config);
      externalAnalyzers.add(checkstyle);

      if (checkstyle.isToolAvailable()) {
        logger.info(
            "Checkstyle analyzer initialized successfully"
                + (checkstyle.canAutoFix() ? " (auto-fix supported)" : ""));
      } else {
        logger.warning("Checkstyle not available: " + checkstyle.getUnavailabilityReason());
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Failed to initialize Checkstyle analyzer", e);
    }

    // Add PMD analyzer
    try {
      PMDAnalyzer pmd = new PMDAnalyzer(config);
      externalAnalyzers.add(pmd);

      if (pmd.isToolAvailable()) {
        logger.info(
            "PMD analyzer initialized successfully"
                + (pmd.canAutoFix() ? " (auto-fix supported)" : ""));
      } else {
        logger.warning("PMD not available: " + pmd.getUnavailabilityReason());
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Failed to initialize PMD analyzer", e);
    }
  }

  /** Initialize custom Spring-specific analyzers. */
  private void initializeCustomAnalyzers() {
    // Enhanced import organizer (replaces the simple one)
    customAnalyzers.add(new ImportOrganizer(config));

    // Keep Spring-specific analyzers as they provide domain expertise
    customAnalyzers.add(new SpringComponentAnalyzer(config));
    customAnalyzers.add(new DesignPatternAnalyzer(config));

    logger.info("Custom Spring analyzers initialized: " + customAnalyzers.size());
  }

  @Override
  public FormatterResult format(Path filePath, String sourceCode) {
    if (sourceCode == null || sourceCode.trim().isEmpty()) {
      return FormatterResult.builder().successful(true).formattedCode(sourceCode).build();
    }

    logger.fine("Formatting file: " + filePath);

    // Create cache keys
    String sourceCodeHash = Integer.toString(sourceCode.hashCode());
    String astCacheKey = filePath.toString() + ":" + sourceCodeHash;
    String analysisCacheKey = "analysis:" + astCacheKey;

    try {
      // Parse source code to AST
      CompilationUnit cu = getOrParseCompilationUnit(astCacheKey, sourceCode);
      if (cu == null) {
        return createParseErrorResult();
      }

      // Track all changes and refactorings
      List<Refactoring> allRefactorings = new ArrayList<>();
      List<FormatterError> allErrors = new ArrayList<>();
      String currentCode = sourceCode;
      CompilationUnit workingCu = cu.clone();

      // Step 1: Apply external tool auto-fixes if enabled
      if (autoFixEnabled && autoFixOnFormat) {
        ExternalAutoFixResult externalFixResult =
            applyExternalToolAutoFixes(workingCu, currentCode);
        if (externalFixResult.hasChanges()) {
          currentCode = externalFixResult.getFixedCode();
          allRefactorings.addAll(externalFixResult.getRefactorings());

          // Re-parse the fixed code
          workingCu = getOrParseCompilationUnit(astCacheKey + ":fixed", currentCode);
          if (workingCu == null) {
            logger.warning("Failed to parse code after external tool fixes");
            workingCu = cu.clone(); // Fallback to original
            currentCode = sourceCode;
          } else {
            if (verboseAutoFix) {
              logger.info(
                  "Applied external tool fixes: "
                      + externalFixResult.getRefactorings().size()
                      + " changes");
            }
          }
        }
        allErrors.addAll(externalFixResult.getRemainingErrors());
      }

      // Step 2: Perform analysis on the current state
      AnalysisResult analysisResult = performAnalysis(analysisCacheKey, workingCu.clone());

      // Step 3: Apply custom analyzer refactorings
      RefactoringResult customRefactoringResult =
          applyCustomRefactorings(workingCu.clone(), analysisResult.errors);
      allRefactorings.addAll(customRefactoringResult.refactorings);
      allErrors.addAll(customRefactoringResult.errors);

      // Step 4: Format the code with JavaParser
      String formattedCode = printer.print(workingCu);

      // Check if code was actually changed by formatting
      boolean codeChanged = !currentCode.equals(formattedCode);
      if (codeChanged) {
        allRefactorings.add(
            new Refactoring(
                "JAVA_FORMATTING",
                1,
                currentCode.split("\n").length,
                "Applied standard Java formatting"));
      }

      // Combine all errors from analysis
      allErrors.addAll(analysisResult.errors);

      // Filter out duplicate errors
      allErrors = removeDuplicateErrors(allErrors);

      // Determine success (no fatal or error level issues)
      boolean successful =
          allErrors.stream()
              .noneMatch(
                  e -> e.getSeverity() == Severity.FATAL || e.getSeverity() == Severity.ERROR);

      // Clean up cache if needed
      cleanupCacheIfNeeded();

      // Log summary if verbose auto-fix is enabled
      if (verboseAutoFix && !allRefactorings.isEmpty()) {
        logger.info("Applied " + allRefactorings.size() + " refactorings to " + filePath);
        allRefactorings.forEach(r -> logger.fine("  - " + r.getDescription()));
      }

      return FormatterResult.builder()
          .successful(successful)
          .formattedCode(formattedCode)
          .errors(allErrors)
          .appliedRefactorings(allRefactorings)
          .build();

    } catch (Exception e) {
      logger.log(Level.SEVERE, "Unexpected error during formatting: " + filePath, e);
      return FormatterResult.builder()
          .successful(false)
          .formattedCode(sourceCode)
          .addError(
              new FormatterError(
                  Severity.FATAL,
                  "Unexpected formatting error: " + e.getMessage(),
                  1,
                  1,
                  "Check the logs for more details"))
          .build();
    }
  }

  /** Apply external tool auto-fixes (Checkstyle, PMD) with priority ordering. */
  private ExternalAutoFixResult applyExternalToolAutoFixes(CompilationUnit cu, String sourceCode) {
    List<Refactoring> allRefactorings = new ArrayList<>();
    List<FormatterError> remainingErrors = new ArrayList<>();
    String currentCode = sourceCode;
    int totalFixesApplied = 0;

    // Get priorities from configuration
    Map<String, Integer> priorities = new HashMap<>();
    priorities.put("checkstyle", config.getGeneralConfig("autoFix.priorities.checkstyle", 1));
    priorities.put("pmd", config.getGeneralConfig("autoFix.priorities.pmd", 2));

    // Sort external analyzers by priority
    List<CodeAnalyzer> sortedAnalyzers =
        externalAnalyzers.stream()
            .sorted(
                (a1, a2) -> {
                  String name1 = a1.getClass().getSimpleName().toLowerCase();
                  String name2 = a2.getClass().getSimpleName().toLowerCase();

                  int priority1 = priorities.getOrDefault(name1.replace("analyzer", ""), 999);
                  int priority2 = priorities.getOrDefault(name2.replace("analyzer", ""), 999);

                  return Integer.compare(priority1, priority2);
                })
            .collect(Collectors.toList());

    // Apply fixes from each external tool in priority order
    for (CodeAnalyzer analyzer : sortedAnalyzers) {
      if (!analyzer.canAutoFix()) {
        continue;
      }

      if (totalFixesApplied >= maxFixesPerFile) {
        logger.warning("Reached maximum fixes per file limit: " + maxFixesPerFile);
        break;
      }

      try {
        // Re-parse the current code state
        CompilationUnit currentCu = parseCode(currentCode);
        if (currentCu == null) {
          logger.warning("Failed to parse code for " + analyzer.getClass().getSimpleName());
          continue;
        }

        // Apply refactorings from this analyzer
        com.codeformatter.plugins.spring.RefactoringResult result =
            analyzer.applyRefactoring(currentCu.clone());

        if (!result.getAppliedRefactorings().isEmpty()) {
          // Get the updated code
          String updatedCode = printer.print(currentCu);

          if (!updatedCode.equals(currentCode)) {
            currentCode = updatedCode;
            allRefactorings.addAll(result.getAppliedRefactorings());
            totalFixesApplied += result.getAppliedRefactorings().size();

            if (verboseAutoFix) {
              logger.info(
                  analyzer.getClass().getSimpleName()
                      + " applied "
                      + result.getAppliedRefactorings().size()
                      + " fixes");
            }
          }
        }

        remainingErrors.addAll(result.getErrors());

      } catch (Exception e) {
        logger.log(
            Level.WARNING,
            "Error applying auto-fixes from " + analyzer.getClass().getSimpleName(),
            e);
        remainingErrors.add(
            new FormatterError(
                Severity.WARNING,
                "Auto-fix error from "
                    + analyzer.getClass().getSimpleName()
                    + ": "
                    + e.getMessage(),
                1,
                1,
                "Manual review may be required"));
      }
    }

    return new ExternalAutoFixResult(sourceCode, currentCode, allRefactorings, remainingErrors);
  }

  /** Parse source code into CompilationUnit. */
  private CompilationUnit parseCode(String sourceCode) {
    try {
      JavaParser parser = new JavaParser();
      ParseResult<CompilationUnit> parseResult = parser.parse(sourceCode);

      if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
        return parseResult.getResult().get();
      } else {
        logger.warning(
            "Failed to parse source code: "
                + parseResult.getProblems().stream()
                    .map(p -> p.getMessage())
                    .reduce("", (a, b) -> a + "; " + b));
        return null;
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Exception during code parsing", e);
      return null;
    }
  }

  /** Remove duplicate errors based on line, column, and message. */
  private List<FormatterError> removeDuplicateErrors(List<FormatterError> errors) {
    Set<String> seen = new HashSet<>();
    List<FormatterError> uniqueErrors = new ArrayList<>();

    for (FormatterError error : errors) {
      String key = error.getLine() + ":" + error.getMessage();
      if (!seen.contains(key)) {
        seen.add(key);
        uniqueErrors.add(error);
      }
    }

    return uniqueErrors;
  }

  /** Get compilation unit from cache or parse it. */
  private CompilationUnit getOrParseCompilationUnit(String cacheKey, String sourceCode) {
    // Check cache first
    cacheLock.readLock().lock();
    try {
      CompilationUnit cached = astCache.get(cacheKey);
      if (cached != null) {
        logger.fine("AST cache hit for: " + cacheKey);
        return cached.clone(); // Return a clone to avoid modification
      }
    } finally {
      cacheLock.readLock().unlock();
    }

    // Parse source code
    CompilationUnit cu = parseCode(sourceCode);
    if (cu == null) {
      return null;
    }

    // Cache the result
    cacheLock.writeLock().lock();
    try {
      astCache.put(cacheKey, cu.clone());
      logger.fine("Cached AST for: " + cacheKey);
    } finally {
      cacheLock.writeLock().unlock();
    }

    return cu;
  }

  /** Perform analysis using all available analyzers. */
  private AnalysisResult performAnalysis(String cacheKey, CompilationUnit cu) {
    // Check cache first
    List<FormatterError> cachedErrors = analysisCache.get(cacheKey);
    if (cachedErrors != null) {
      logger.fine("Analysis cache hit for: " + cacheKey);
      return new AnalysisResult(new ArrayList<>(cachedErrors));
    }

    List<FormatterError> allErrors = new ArrayList<>();
    Map<String, Integer> analyzerErrorCounts = new HashMap<>();

    // Run external analyzers for analysis (not auto-fix)
    for (CodeAnalyzer analyzer : externalAnalyzers) {
      try {
        String analyzerName = analyzer.getClass().getSimpleName();
        AnalyzerResult result = analyzer.analyze(cu.clone());

        allErrors.addAll(result.getErrors());
        analyzerErrorCounts.put(analyzerName, result.getErrors().size());

        logger.fine(analyzerName + " found " + result.getErrors().size() + " issues");
      } catch (Exception e) {
        logger.log(
            Level.WARNING,
            "Error running external analyzer: " + analyzer.getClass().getSimpleName(),
            e);
        allErrors.add(
            new FormatterError(
                Severity.WARNING,
                "External analyzer error: " + e.getMessage(),
                1,
                1,
                "Check analyzer configuration and tool availability"));
      }
    }

    // Run custom analyzers for analysis
    for (CodeAnalyzer analyzer : customAnalyzers) {
      try {
        String analyzerName = analyzer.getClass().getSimpleName();
        AnalyzerResult result = analyzer.analyze(cu.clone());

        allErrors.addAll(result.getErrors());
        analyzerErrorCounts.put(analyzerName, result.getErrors().size());

        logger.fine(analyzerName + " found " + result.getErrors().size() + " issues");
      } catch (Exception e) {
        logger.log(
            Level.WARNING,
            "Error running custom analyzer: " + analyzer.getClass().getSimpleName(),
            e);
        allErrors.add(
            new FormatterError(
                Severity.WARNING,
                "Custom analyzer error: " + e.getMessage(),
                1,
                1,
                "This may be a bug in the formatter"));
      }
    }

    // Log analysis summary
    logger.fine(
        "Analysis complete: "
            + allErrors.size()
            + " total issues from "
            + analyzerErrorCounts.size()
            + " analyzers");

    // Cache the results
    analysisCache.put(cacheKey, new ArrayList<>(allErrors));

    return new AnalysisResult(allErrors);
  }

  /** Apply refactorings from custom analyzers that support auto-fixing. */
  private RefactoringResult applyCustomRefactorings(
      CompilationUnit cu, List<FormatterError> analysisErrors) {
    List<Refactoring> allRefactorings = new ArrayList<>();
    List<FormatterError> refactoringErrors = new ArrayList<>();

    // Apply refactorings from custom analyzers (they're more reliable for refactoring)
    for (CodeAnalyzer analyzer : customAnalyzers) {
      if (analyzer.canAutoFix()) {
        try {
          com.codeformatter.plugins.spring.RefactoringResult result = analyzer.applyRefactoring(cu);
          allRefactorings.addAll(result.getAppliedRefactorings());
          refactoringErrors.addAll(result.getErrors());

          if (!result.getAppliedRefactorings().isEmpty()) {
            logger.fine(
                analyzer.getClass().getSimpleName()
                    + " applied "
                    + result.getAppliedRefactorings().size()
                    + " refactorings");
          }
        } catch (Exception e) {
          logger.log(
              Level.WARNING,
              "Error applying refactorings from: " + analyzer.getClass().getSimpleName(),
              e);
          refactoringErrors.add(
              new FormatterError(
                  Severity.WARNING,
                  "Refactoring error: " + e.getMessage(),
                  1,
                  1,
                  "Manual refactoring may be needed"));
        }
      }
    }

    return new RefactoringResult(allRefactorings, refactoringErrors);
  }

  /** Create a parse error result. */
  private FormatterResult createParseErrorResult() {
    return FormatterResult.builder()
        .successful(false)
        .formattedCode(null)
        .addError(
            new FormatterError(
                Severity.FATAL,
                "Failed to parse Java source code",
                1,
                1,
                "Check for syntax errors in the source code"))
        .build();
  }

  /** Clean up caches if they're getting too large. */
  private void cleanupCacheIfNeeded() {
    if (astCache.size() > CACHE_CLEANUP_THRESHOLD
        || analysisCache.size() > CACHE_CLEANUP_THRESHOLD) {
      cacheLock.writeLock().lock();
      try {
        // Simple LRU eviction - remove oldest entries
        if (astCache.size() > MAX_CACHE_SIZE) {
          Iterator<Map.Entry<String, CompilationUnit>> iter = astCache.entrySet().iterator();
          int toRemove = astCache.size() - MAX_CACHE_SIZE;
          for (int i = 0; i < toRemove && iter.hasNext(); i++) {
            iter.next();
            iter.remove();
          }
          logger.fine("Cleaned up AST cache: removed " + toRemove + " entries");
        }

        if (analysisCache.size() > MAX_CACHE_SIZE) {
          Iterator<Map.Entry<String, List<FormatterError>>> iter =
              analysisCache.entrySet().iterator();
          int toRemove = analysisCache.size() - MAX_CACHE_SIZE;
          for (int i = 0; i < toRemove && iter.hasNext(); i++) {
            iter.next();
            iter.remove();
          }
          logger.fine("Cleaned up analysis cache: removed " + toRemove + " entries");
        }
      } finally {
        cacheLock.writeLock().unlock();
      }
    }
  }

  /** Get a summary of available analyzers and their status. */
  public AnalyzerStatus getAnalyzerStatus() {
    Map<String, Boolean> externalToolStatus = new HashMap<>();
    Map<String, String> unavailabilityReasons = new HashMap<>();
    Map<String, Boolean> autoFixStatus = new HashMap<>();

    for (CodeAnalyzer analyzer : externalAnalyzers) {
      String name = analyzer.getClass().getSimpleName();
      if (analyzer instanceof CheckstyleAnalyzer) {
        CheckstyleAnalyzer checkstyle = (CheckstyleAnalyzer) analyzer;
        externalToolStatus.put(name, checkstyle.isToolAvailable());
        autoFixStatus.put(name, checkstyle.canAutoFix());
        if (!checkstyle.isToolAvailable()) {
          unavailabilityReasons.put(name, checkstyle.getUnavailabilityReason());
        }
      } else if (analyzer instanceof PMDAnalyzer) {
        PMDAnalyzer pmd = (PMDAnalyzer) analyzer;
        externalToolStatus.put(name, pmd.isToolAvailable());
        autoFixStatus.put(name, pmd.canAutoFix());
        if (!pmd.isToolAvailable()) {
          unavailabilityReasons.put(name, pmd.getUnavailabilityReason());
        }
      }
    }

    List<String> customAnalyzerNames =
        customAnalyzers.stream().map(a -> a.getClass().getSimpleName()).toList();

    return new AnalyzerStatus(
        externalToolStatus, unavailabilityReasons, customAnalyzerNames, autoFixStatus);
  }

  @Override
  public void close() {
    logger.info("Closing SpringBootFormatter");

    cacheLock.writeLock().lock();
    try {
      astCache.clear();
      analysisCache.clear();
      logger.fine("Cleared all caches");
    } finally {
      cacheLock.writeLock().unlock();
    }

    // Close any analyzers that implement AutoCloseable
    List<CodeAnalyzer> allAnalyzers = new ArrayList<>();
    allAnalyzers.addAll(externalAnalyzers);
    allAnalyzers.addAll(customAnalyzers);

    for (CodeAnalyzer analyzer : allAnalyzers) {
      if (analyzer instanceof AutoCloseable) {
        try {
          ((AutoCloseable) analyzer).close();
        } catch (Exception e) {
          logger.log(
              Level.WARNING, "Error closing analyzer: " + analyzer.getClass().getSimpleName(), e);
        }
      }
    }
  }

  // Helper classes
  private static class AnalysisResult {
    final List<FormatterError> errors;

    AnalysisResult(List<FormatterError> errors) {
      this.errors = errors;
    }
  }

  private static class RefactoringResult {
    final List<Refactoring> refactorings;
    final List<FormatterError> errors;

    RefactoringResult(List<Refactoring> refactorings, List<FormatterError> errors) {
      this.refactorings = refactorings;
      this.errors = errors;
    }
  }

  private static class ExternalAutoFixResult {
    final String originalCode;
    final String fixedCode;
    final List<Refactoring> refactorings;
    final List<FormatterError> remainingErrors;

    ExternalAutoFixResult(
        String originalCode,
        String fixedCode,
        List<Refactoring> refactorings,
        List<FormatterError> remainingErrors) {
      this.originalCode = originalCode;
      this.fixedCode = fixedCode;
      this.refactorings = refactorings;
      this.remainingErrors = remainingErrors;
    }

    boolean hasChanges() {
      return !originalCode.equals(fixedCode);
    }

    String getFixedCode() {
      return fixedCode;
    }

    List<Refactoring> getRefactorings() {
      return refactorings;
    }

    List<FormatterError> getRemainingErrors() {
      return remainingErrors;
    }
  }

  public static class AnalyzerStatus {
    private final Map<String, Boolean> externalToolStatus;
    private final Map<String, String> unavailabilityReasons;
    private final List<String> customAnalyzers;
    private final Map<String, Boolean> autoFixStatus;

    public AnalyzerStatus(
        Map<String, Boolean> externalToolStatus,
        Map<String, String> unavailabilityReasons,
        List<String> customAnalyzers,
        Map<String, Boolean> autoFixStatus) {
      this.externalToolStatus = externalToolStatus;
      this.unavailabilityReasons = unavailabilityReasons;
      this.customAnalyzers = customAnalyzers;
      this.autoFixStatus = autoFixStatus;
    }

    public Map<String, Boolean> getExternalToolStatus() {
      return externalToolStatus;
    }

    public Map<String, String> getUnavailabilityReasons() {
      return unavailabilityReasons;
    }

    public List<String> getCustomAnalyzers() {
      return customAnalyzers;
    }

    public Map<String, Boolean> getAutoFixStatus() {
      return autoFixStatus;
    }

    public boolean areAllExternalToolsAvailable() {
      return externalToolStatus.values().stream().allMatch(Boolean::valueOf);
    }

    public int getAvailableExternalToolCount() {
      return (int) externalToolStatus.values().stream().filter(Boolean::valueOf).count();
    }

    public int getAutoFixCapableToolCount() {
      return (int) autoFixStatus.values().stream().filter(Boolean::valueOf).count();
    }
  }
}
