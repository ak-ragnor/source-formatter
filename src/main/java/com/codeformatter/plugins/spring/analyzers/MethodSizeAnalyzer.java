package com.codeformatter.plugins.spring.analyzers;

import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.CodeAnalyzer;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.ArrayList;
import java.util.List;

public class MethodSizeAnalyzer implements CodeAnalyzer {
  private final int maxMethodLines;
  private final int maxMethodComplexity;

  public MethodSizeAnalyzer(FormatterConfig config) {
    this.maxMethodLines = config.getPluginConfig("spring", "maxMethodLines", 50);
    this.maxMethodComplexity = config.getPluginConfig("spring", "maxMethodComplexity", 15);
  }

  @Override
  public AnalyzerResult analyze(CompilationUnit cu) {
    List<FormatterError> errors = new ArrayList<>();

    cu.findAll(MethodDeclaration.class)
        .forEach(
            method -> {
              if (method.getBody().isPresent()) {

                BlockStmt body = method.getBody().get();
                int lineCount = _countMethodLines(body);

                if (lineCount > maxMethodLines) {
                  int line = method.getBegin().map(p -> p.line).orElse(1);
                  int column = method.getBegin().map(p -> p.column).orElse(1);

                  errors.add(
                      new FormatterError(
                          Severity.ERROR,
                          "Method '"
                              + method.getNameAsString()
                              + "' is too long ("
                              + lineCount
                              + " lines, max allowed is "
                              + maxMethodLines
                              + ")",
                          line,
                          column,
                          "Consider breaking this method into smaller helper methods"));
                }

                int complexity = _calculateComplexity(body);
                if (complexity > maxMethodComplexity) {
                  int line = method.getBegin().map(p -> p.line).orElse(1);
                  int column = method.getBegin().map(p -> p.column).orElse(1);

                  errors.add(
                      new FormatterError(
                          Severity.ERROR,
                          "Method '"
                              + method.getNameAsString()
                              + "' is too complex (complexity "
                              + complexity
                              + ", max allowed is "
                              + maxMethodComplexity
                              + ")",
                          line,
                          column,
                          "Consider extracting complex logic into separate methods"));
                }
              }
            });

    return new AnalyzerResult(errors);
  }

  @Override
  public boolean canAutoFix() {
    return false;
  }

  @Override
  public RefactoringResult applyRefactoring(CompilationUnit cu) {
    return new RefactoringResult(new ArrayList<>(), new ArrayList<>());
  }

  private int _countMethodLines(BlockStmt body) {
    if (body.getEnd().isPresent() && body.getBegin().isPresent()) {
      return body.getEnd().get().line - body.getBegin().get().line + 1;
    }
    return 0;
  }

  private int _calculateComplexity(BlockStmt body) {
    int complexity = 1;

    complexity += body.findAll(com.github.javaparser.ast.stmt.IfStmt.class).size();
    complexity += body.findAll(com.github.javaparser.ast.stmt.ForStmt.class).size();
    complexity += body.findAll(com.github.javaparser.ast.stmt.ForEachStmt.class).size();
    complexity += body.findAll(com.github.javaparser.ast.stmt.WhileStmt.class).size();
    complexity += body.findAll(com.github.javaparser.ast.stmt.DoStmt.class).size();
    complexity += body.findAll(com.github.javaparser.ast.stmt.SwitchEntry.class).size();

    return complexity;
  }
}
