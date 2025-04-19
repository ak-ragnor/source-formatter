package com.codeformatter.plugins.spring.analyzers;

import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.spring.AnalyzerResult;
import com.codeformatter.plugins.spring.CodeAnalyzer;
import com.codeformatter.plugins.spring.RefactoringResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class CodeStyleAnalyzer implements CodeAnalyzer {
  private final FormatterConfig config;
  private final int indentSize;
  private final int lineLength;
  private final boolean useTabs;

  private static final Pattern CAMEL_CASE_METHOD = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
  private static final Pattern CAMEL_CASE_VARIABLE = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
  private static final Pattern SCREAMING_SNAKE_CASE =
      Pattern.compile("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$");

  public CodeStyleAnalyzer(FormatterConfig config) {
    this.config = config;
    this.indentSize = config.getGeneralConfig("indentSize", 4);
    this.lineLength = config.getGeneralConfig("lineLength", 100);
    this.useTabs = config.getGeneralConfig("useTabs", false);
  }

  @Override
  public AnalyzerResult analyze(CompilationUnit cu) {
    List<FormatterError> errors = new ArrayList<>();

    _checkMethodNaming(cu, errors);
    _checkVariableNaming(cu, errors);
    _checkLineLengths(cu, errors);
    _checkMethodChaining(cu, errors);

    return new AnalyzerResult(errors);
  }

  @Override
  public boolean canAutoFix() {

    return true;
  }

  @Override
  public RefactoringResult applyRefactoring(CompilationUnit cu) {
    List<Refactoring> refactorings = new ArrayList<>();
    List<FormatterError> errors = new ArrayList<>();

    _fixLineLengths(cu, refactorings, errors);

    _fixMethodChaining(cu, refactorings, errors);

    return new RefactoringResult(refactorings, errors);
  }

  private void _checkMethodNaming(CompilationUnit cu, List<FormatterError> errors) {
    cu.findAll(MethodDeclaration.class)
        .forEach(
            method -> {
              String methodName = method.getNameAsString();

              if (!CAMEL_CASE_METHOD.matcher(methodName).matches()) {
                if (!methodName.startsWith("_")) {
                  errors.add(
                      new FormatterError(
                          Severity.WARNING,
                          "Method name '" + methodName + "' doesn't follow camelCase convention",
                          method.getBegin().get().line,
                          method.getBegin().get().column,
                          "Rename method to follow camelCase convention"));
                }
              }
            });
  }

  private void _checkVariableNaming(CompilationUnit cu, List<FormatterError> errors) {
    cu.findAll(VariableDeclarator.class)
        .forEach(
            variable -> {
              String varName = variable.getNameAsString();

              boolean isConstant =
                  variable.getParentNode().isPresent()
                      && variable.getParentNode().get().toString().contains("final")
                      && variable.getParentNode().get().toString().contains("static");

              if (isConstant) {
                if (!SCREAMING_SNAKE_CASE.matcher(varName).matches()) {
                  errors.add(
                      new FormatterError(
                          Severity.WARNING,
                          "Constant '" + varName + "' should use UPPER_SNAKE_CASE",
                          variable.getBegin().get().line,
                          variable.getBegin().get().column,
                          "Rename constant to follow UPPER_SNAKE_CASE convention"));
                }
              } else {
                if (!CAMEL_CASE_VARIABLE.matcher(varName).matches() && !varName.startsWith("_")) {
                  errors.add(
                      new FormatterError(
                          Severity.WARNING,
                          "Variable name '" + varName + "' doesn't follow camelCase convention",
                          variable.getBegin().get().line,
                          variable.getBegin().get().column,
                          "Rename variable to follow camelCase convention"));
                }
              }
            });
  }

  private void _checkLineLengths(CompilationUnit cu, List<FormatterError> errors) {

    cu.findAll(BlockStmt.class)
        .forEach(
            block -> {
              block
                  .getStatements()
                  .forEach(
                      stmt -> {
                        String stmtStr = stmt.toString();

                        if (stmtStr.length() > lineLength && !stmtStr.contains("\n")) {
                          errors.add(
                              new FormatterError(
                                  Severity.INFO,
                                  "Statement may exceed line length limit of "
                                      + lineLength
                                      + " characters",
                                  stmt.getBegin().get().line,
                                  stmt.getBegin().get().column,
                                  "Consider breaking the statement across multiple lines"));
                        }
                      });
            });
  }

  private void _checkMethodChaining(CompilationUnit cu, List<FormatterError> errors) {
    cu.findAll(MethodCallExpr.class)
        .forEach(
            call -> {
              if (call.getScope().isPresent() && call.getScope().get() instanceof MethodCallExpr) {
                MethodCallExpr scope = (MethodCallExpr) call.getScope().get();

                if (scope.getScope().isPresent()
                    && scope.getScope().get() instanceof MethodCallExpr) {
                  String callStr = call.toString();
                  if (!callStr.contains("\n") && callStr.length() > 50) {
                    errors.add(
                        new FormatterError(
                            Severity.INFO,
                            "Long method chain detected that could reduce readability",
                            call.getBegin().get().line,
                            call.getBegin().get().column,
                            "Consider breaking the method chain into multiple lines with each method call on its own line"));
                  }
                }
              }
            });
  }

  private void _fixLineLengths(
      CompilationUnit cu, List<Refactoring> refactorings, List<FormatterError> errors) {
    boolean hasChanges = false;

    for (com.github.javaparser.ast.stmt.BlockStmt block :
        cu.findAll(com.github.javaparser.ast.stmt.BlockStmt.class)) {
      for (int i = 0; i < block.getStatements().size(); i++) {
        com.github.javaparser.ast.stmt.Statement stmt = block.getStatement(i);
        String stmtStr = stmt.toString();

        if (stmtStr.length() > lineLength && !stmtStr.contains("\n")) {
          // Try to break this statement into multiple lines

          if (stmt instanceof com.github.javaparser.ast.stmt.ExpressionStmt) {
            com.github.javaparser.ast.expr.Expression expr =
                ((com.github.javaparser.ast.stmt.ExpressionStmt) stmt).getExpression();

            if (expr instanceof com.github.javaparser.ast.expr.MethodCallExpr) {
              com.github.javaparser.ast.expr.MethodCallExpr methodCall =
                  (com.github.javaparser.ast.expr.MethodCallExpr) expr;

              // Format method call with line breaks
              _formatMethodCall(methodCall);
              hasChanges = true;
            } else if (expr instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr) {
              com.github.javaparser.ast.expr.VariableDeclarationExpr varExpr =
                  (com.github.javaparser.ast.expr.VariableDeclarationExpr) expr;

              // Format variable declaration with line breaks
              if (varExpr.getVariables().size() > 0) {
                com.github.javaparser.ast.body.VariableDeclarator var = varExpr.getVariable(0);
                if (var.getInitializer().isPresent()) {
                  com.github.javaparser.ast.expr.Expression init = var.getInitializer().get();

                  // Format complex initializers with line breaks
                  if (init instanceof com.github.javaparser.ast.expr.ObjectCreationExpr) {
                    _formatObjectCreation((com.github.javaparser.ast.expr.ObjectCreationExpr) init);
                    hasChanges = true;
                  } else if (init instanceof com.github.javaparser.ast.expr.ArrayCreationExpr) {
                    _formatArrayCreation((com.github.javaparser.ast.expr.ArrayCreationExpr) init);
                    hasChanges = true;
                  }
                }
              }
            } else if (expr instanceof com.github.javaparser.ast.expr.BinaryExpr) {
              // Format binary expressions with line breaks at operators
              _formatBinaryExpr((com.github.javaparser.ast.expr.BinaryExpr) expr);
              hasChanges = true;
            }
          }
        }
      }
    }

    if (hasChanges) {
      refactorings.add(
          new Refactoring(
              "LINE_LENGTH_FIX",
              1,
              1,
              "Reformatted long lines by adding line breaks and proper indentation"));
    }
  }

  private void _formatMethodCall(com.github.javaparser.ast.expr.MethodCallExpr methodCall) {
    if (methodCall.getArguments().size() <= 2) {
      return; // No need to format if just a few arguments
    }

    // Add position information to force pretty printing with line breaks
    if (methodCall.getBegin().isPresent() && methodCall.getEnd().isPresent()) {
      for (int i = 0; i < methodCall.getArguments().size(); i++) {
        com.github.javaparser.ast.expr.Expression arg = methodCall.getArgument(i);

        // Try to set positions to force argument to appear on a new line
        if (arg.getBegin().isPresent()) {
          arg.setRange(
              new com.github.javaparser.Range(
                  new com.github.javaparser.Position(
                      methodCall.getBegin().get().line + i + 1,
                      methodCall.getBegin().get().column + indentSize),
                  arg.getEnd().get()));
        }
      }
    }
  }

  private void _formatObjectCreation(com.github.javaparser.ast.expr.ObjectCreationExpr expr) {
    if (!expr.getArguments().isEmpty()) {
      // Format the arguments like a method call but handle differently
      if (expr.getArguments().size() > 2 && expr.getBegin().isPresent()) {
        for (int i = 0; i < expr.getArguments().size(); i++) {
          com.github.javaparser.ast.expr.Expression arg = expr.getArgument(i);

          // Try to set positions to force argument to appear on a new line
          if (arg.getBegin().isPresent()) {
            arg.setRange(
                new com.github.javaparser.Range(
                    new com.github.javaparser.Position(
                        expr.getBegin().get().line + i + 1,
                        expr.getBegin().get().column + indentSize),
                    arg.getEnd().get()));
          }
        }
      }
    }

    if (expr.getAnonymousClassBody().isPresent()) {
      // Format anonymous class declarations
      expr.getAnonymousClassBody()
          .get()
          .forEach(
              member -> {
                // Position members on separate lines
                if (member.getBegin().isPresent()) {
                  member.setRange(
                      new com.github.javaparser.Range(
                          new com.github.javaparser.Position(
                              member.getBegin().get().line + 1, indentSize),
                          member.getEnd().get()));
                }
              });
    }
  }

  private void _formatArrayCreation(com.github.javaparser.ast.expr.ArrayCreationExpr expr) {
    if (expr.getInitializer().isPresent()) {
      com.github.javaparser.ast.expr.ArrayInitializerExpr init = expr.getInitializer().get();

      if (init.getValues().size() > 3) {
        // Format array initializer with one element per line
        for (int i = 0; i < init.getValues().size(); i++) {
          com.github.javaparser.ast.expr.Expression value =
              init.getValues().get(i); // Use getValues().get(i) instead of getValue(i)

          if (value.getBegin().isPresent()) {
            value.setRange(
                new com.github.javaparser.Range(
                    new com.github.javaparser.Position(
                        expr.getBegin().get().line + i + 1,
                        expr.getBegin().get().column + indentSize),
                    value.getEnd().get()));
          }
        }
      }
    }
  }

  private void _formatBinaryExpr(com.github.javaparser.ast.expr.BinaryExpr expr) {
    // Add a line break before the operator
    if (expr.getRight().getBegin().isPresent() && expr.getBegin().isPresent()) {
      expr.getRight()
          .setRange(
              new com.github.javaparser.Range(
                  new com.github.javaparser.Position(
                      expr.getBegin().get().line + 1, expr.getBegin().get().column + indentSize),
                  expr.getRight().getEnd().get()));
    }

    // If right side is also a binary expression, format it too
    if (expr.getRight() instanceof com.github.javaparser.ast.expr.BinaryExpr) {
      _formatBinaryExpr((com.github.javaparser.ast.expr.BinaryExpr) expr.getRight());
    }
  }

  private void _fixMethodChaining(
      CompilationUnit cu, List<Refactoring> refactorings, List<FormatterError> errors) {
    boolean hasChanges = false;

    List<com.github.javaparser.ast.expr.MethodCallExpr> chainedCalls = new ArrayList<>();

    // Find method chains
    for (com.github.javaparser.ast.expr.MethodCallExpr call :
        cu.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)) {
      if (call.getScope().isPresent()
          && call.getScope().get() instanceof com.github.javaparser.ast.expr.MethodCallExpr) {
        // Count the chain length
        int chainLength = 1;
        com.github.javaparser.ast.expr.Expression scope = call.getScope().get();

        while (scope instanceof com.github.javaparser.ast.expr.MethodCallExpr
            && ((com.github.javaparser.ast.expr.MethodCallExpr) scope).getScope().isPresent()) {
          chainLength++;
          scope = ((com.github.javaparser.ast.expr.MethodCallExpr) scope).getScope().get();
        }

        if (chainLength > 2) {
          chainedCalls.add(call);
        }
      }
    }

    // Process each method chain
    for (com.github.javaparser.ast.expr.MethodCallExpr topCall : chainedCalls) {
      // Format the chain by adding line breaks and indentation
      _formatMethodChain(topCall);
      hasChanges = true;
    }

    if (hasChanges) {
      refactorings.add(
          new Refactoring(
              "METHOD_CHAIN_FORMAT",
              1,
              1,
              "Reformatted method chains with line breaks and proper indentation"));
    }
  }

  private void _formatMethodChain(com.github.javaparser.ast.expr.MethodCallExpr methodCall) {
    if (methodCall.getScope().isEmpty()) return;

    com.github.javaparser.ast.expr.Expression scope = methodCall.getScope().get();
    if (!(scope instanceof com.github.javaparser.ast.expr.MethodCallExpr)) return;

    com.github.javaparser.ast.expr.MethodCallExpr scopeCall =
        (com.github.javaparser.ast.expr.MethodCallExpr) scope;

    // Add indentation to have each method call on a new line
    if (methodCall.getBegin().isPresent() && scopeCall.getBegin().isPresent()) {
      methodCall.setRange(
          new com.github.javaparser.Range(
              new com.github.javaparser.Position(
                  scopeCall.getBegin().get().line + 1,
                  scopeCall.getBegin().get().column + indentSize),
              methodCall.getEnd().get()));
    }

    // Recursively format the chain
    _formatMethodChain(scopeCall);
  }
}
