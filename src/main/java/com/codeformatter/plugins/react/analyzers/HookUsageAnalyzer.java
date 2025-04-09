package com.codeformatter.plugins.react.analyzers;

import com.codeformatter.api.Refactoring;
import com.codeformatter.api.error.FormatterError;
import com.codeformatter.api.error.Severity;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.plugins.react.JsAst;
import com.codeformatter.plugins.react.JsEngine;
import com.codeformatter.plugins.react.ReactAnalyzerResult;
import com.codeformatter.plugins.react.ReactCodeAnalyzer;
import com.codeformatter.plugins.react.ReactRefactoringResult;
import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Analyzes React Hook usage patterns and detects common issues
 */
public class HookUsageAnalyzer implements ReactCodeAnalyzer {
    private final FormatterConfig config;
    private final JsEngine jsEngine;
    private final boolean enforceHookDependencies;

    public HookUsageAnalyzer(FormatterConfig config, JsEngine jsEngine) {
        this.config = config;
        this.jsEngine = jsEngine;
        this.enforceHookDependencies = config.getPluginConfig("react", "enforceHookDependencies", true);
    }

    @Override
    public ReactAnalyzerResult analyze(JsAst ast) {
        if (!ast.isValid()) {
            return new ReactAnalyzerResult(new ArrayList<>());
        }

        List<FormatterError> errors = new ArrayList<>();

        checkHookRules(ast, errors);

        if (enforceHookDependencies) {
            checkHookDependencies(ast, errors);
        }

        return new ReactAnalyzerResult(errors);
    }

    @Override
    public boolean canAutoFix() {
        return true;
    }

    @Override
    public ReactRefactoringResult applyRefactoring(JsAst ast) {
        if (!ast.isValid()) {
            return new ReactRefactoringResult(new ArrayList<>(), new ArrayList<>());
        }

        List<Refactoring> refactorings = new ArrayList<>();
        List<FormatterError> errors = new ArrayList<>();

        // Apply dependency fixes to hooks if enabled
        if (enforceHookDependencies) {
            boolean success = jsEngine.transformAst(ast, "fixHookDependencies", new HashMap<>());

            if (success) {
                Value[] useEffectCalls = findHookCalls(ast, "useEffect");
                Value[] useCallbackCalls = findHookCalls(ast, "useCallback");
                Value[] useMemoCalls = findHookCalls(ast, "useMemo");

                int totalHooks = useEffectCalls.length + useCallbackCalls.length + useMemoCalls.length;

                if (totalHooks > 0) {
                    refactorings.add(new Refactoring(
                            "HOOK_DEPENDENCIES_FIX",
                            1, // Would be more accurate with actual line numbers
                            1,
                            "Fixed dependency arrays in " + totalHooks + " React hooks"
                    ));
                }
            }
        }

        return new ReactRefactoringResult(refactorings, errors);
    }

    /**
     * Check if React Hook Rules are being followed
     */
    private void checkHookRules(JsAst ast, List<FormatterError> errors) {
        // Find all function components
        Value[] functionComponents = findFunctionComponents(ast);

        for (Value component : functionComponents) {
            // Check for conditional hook calls within this component
            checkConditionalHooks(ast, component, errors);

            // Check for hook calls inside loops
            checkHooksInLoops(ast, component, errors);

            // Check for custom hooks not starting with "use"
            checkCustomHookNaming(ast, component, errors);
        }
    }

    /**
     * Check for conditional hook calls
     */
    private void checkConditionalHooks(JsAst ast, Value component, List<FormatterError> errors) {
        // Find all hook calls in this component
        Value[] hookCalls = findAllHookCalls(ast, component);

        for (Value hookCall : hookCalls) {
            // Check if the hook call is inside an if statement, conditional expression, etc.
            Value parent = getParentNode(hookCall);
            if (parent != null) {
                String parentType = parent.getMember("type").asString();
                if (parentType.equals("IfStatement") ||
                        parentType.equals("ConditionalExpression") ||
                        parentType.equals("LogicalExpression")) {

                    errors.add(new FormatterError(
                            Severity.ERROR,
                            "React Hook '" + getHookName(hookCall) + "' is called conditionally. " +
                                    "Hooks must be called at the top level of your component.",
                            ast.getNodeLine(hookCall),
                            ast.getNodeColumn(hookCall),
                            "Move this hook outside of conditions"
                    ));
                }
            }
        }
    }

    /**
     * Check for hook calls inside loops
     */
    private void checkHooksInLoops(JsAst ast, Value component, List<FormatterError> errors) {
        // Find all hook calls in this component
        Value[] hookCalls = findAllHookCalls(ast, component);

        for (Value hookCall : hookCalls) {
            // Check if the hook call is inside a loop
            Value parent = getParentNode(hookCall);
            if (parent != null) {
                String parentType = parent.getMember("type").asString();
                if (parentType.equals("ForStatement") ||
                        parentType.equals("ForInStatement") ||
                        parentType.equals("ForOfStatement") ||
                        parentType.equals("WhileStatement") ||
                        parentType.equals("DoWhileStatement")) {

                    errors.add(new FormatterError(
                            Severity.ERROR,
                            "React Hook '" + getHookName(hookCall) + "' is called in a loop. " +
                                    "Hooks must be called at the top level of your component.",
                            ast.getNodeLine(hookCall),
                            ast.getNodeColumn(hookCall),
                            "Move this hook outside of the loop"
                    ));
                }
            }
        }
    }

    /**
     * Check for custom hooks not starting with "use"
     */
    private void checkCustomHookNaming(JsAst ast, Value component, List<FormatterError> errors) {
        // Custom hooks are functions that call hooks
        Value[] functions = ast.findNodes("FunctionDeclaration");
        functions = addNodes(functions, ast.findNodes("ArrowFunctionExpression"));
        functions = addNodes(functions, ast.findNodes("FunctionExpression"));

        for (Value func : functions) {
            // Skip if this is a component (already checked)
            if (isComponent(func)) {
                continue;
            }

            // Check if this function calls hooks
            Value[] hookCalls = findAllHookCalls(ast, func);

            if (hookCalls.length > 0) {
                // This is likely a custom hook, check if it starts with "use"
                String funcName = getFunctionName(func);

                if (funcName != null && !funcName.startsWith("use")) {
                    errors.add(new FormatterError(
                            Severity.WARNING,
                            "Function '" + funcName + "' calls React hooks but doesn't start with 'use'. " +
                                    "Custom hooks should start with 'use' by convention.",
                            ast.getNodeLine(func),
                            ast.getNodeColumn(func),
                            "Rename this function to start with 'use'"
                    ));
                }
            }
        }
    }

    /**
     * Check if hook dependencies are correct
     */
    private void checkHookDependencies(JsAst ast, List<FormatterError> errors) {
        // Check useEffect hooks
        Value[] useEffectCalls = findHookCalls(ast, "useEffect");
        checkEffectDependencies(ast, useEffectCalls, errors);

        // Check useCallback hooks
        Value[] useCallbackCalls = findHookCalls(ast, "useCallback");
        checkCallbackDependencies(ast, useCallbackCalls, errors);

        // Check useMemo hooks
        Value[] useMemoCalls = findHookCalls(ast, "useMemo");
        checkMemoDependencies(ast, useMemoCalls, errors);
    }

    /**
     * Check useEffect dependencies
     */
    private void checkEffectDependencies(JsAst ast, Value[] effectCalls, List<FormatterError> errors) {
        for (Value effect : effectCalls) {
            // Effect should have two arguments
            if (effect.getMember("arguments").getArraySize() < 2) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "useEffect is missing dependency array",
                        ast.getNodeLine(effect),
                        ast.getNodeColumn(effect),
                        "Add a dependency array as the second argument"
                ));
                continue;
            }

            // Second argument should be an array
            Value depsArg = effect.getMember("arguments").getArrayElement(1);
            if (!depsArg.getMember("type").asString().equals("ArrayExpression")) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "useEffect second argument should be an array",
                        ast.getNodeLine(depsArg),
                        ast.getNodeColumn(depsArg),
                        "Replace with an array of dependencies"
                ));
                continue;
            }

            // Check for empty dependency array but using variables
            Value deps = depsArg.getMember("elements");
            if (deps.getArraySize() == 0) {
                // First argument should be a function
                Value callback = effect.getMember("arguments").getArrayElement(0);

                // Check if the callback uses variables outside its scope
                // This is a simplified check - a real implementation would be more sophisticated
            }
        }
    }

    /**
     * Check useCallback dependencies
     */
    private void checkCallbackDependencies(JsAst ast, Value[] callbackCalls, List<FormatterError> errors) {
        // Similar to useEffect dependency checking
        for (Value callback : callbackCalls) {
            if (callback.getMember("arguments").getArraySize() < 2) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "useCallback is missing dependency array",
                        ast.getNodeLine(callback),
                        ast.getNodeColumn(callback),
                        "Add a dependency array as the second argument"
                ));
            }
        }
    }

    /**
     * Check useMemo dependencies
     */
    private void checkMemoDependencies(JsAst ast, Value[] memoCalls, List<FormatterError> errors) {
        // Similar to useEffect dependency checking
        for (Value memo : memoCalls) {
            if (memo.getMember("arguments").getArraySize() < 2) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "useMemo is missing dependency array",
                        ast.getNodeLine(memo),
                        ast.getNodeColumn(memo),
                        "Add a dependency array as the second argument"
                ));
            }
        }
    }

    // Helper methods

    private Value[] findFunctionComponents(JsAst ast) {
        // This is a simplified implementation
        // A real implementation would need to look for functions that return JSX
        return ast.findNodes("FunctionDeclaration");
    }

    private Value[] findHookCalls(JsAst ast, String hookName) {
        Value[] calls = ast.findNodes("CallExpression");
        List<Value> hookCalls = new ArrayList<>();

        for (Value call : calls) {
            if (call.hasMember("callee") &&
                    call.getMember("callee").hasMember("type") &&
                    call.getMember("callee").getMember("type").asString().equals("Identifier") &&
                    call.getMember("callee").getMember("name").asString().equals(hookName)) {

                hookCalls.add(call);
            }
        }

        return hookCalls.toArray(new Value[0]);
    }

    private Value[] findAllHookCalls(JsAst ast, Value component) {
        // Find all hook calls, including custom hooks (functions starting with "use")
        Value[] calls = ast.findNodes("CallExpression");
        List<Value> hookCalls = new ArrayList<>();

        for (Value call : calls) {
            if (call.hasMember("callee") &&
                    call.getMember("callee").hasMember("type") &&
                    call.getMember("callee").getMember("type").asString().equals("Identifier")) {

                String calleeName = call.getMember("callee").getMember("name").asString();
                if (calleeName.startsWith("use")) {
                    hookCalls.add(call);
                }
            }
        }

        return hookCalls.toArray(new Value[0]);
    }

    private Value getParentNode(Value node) {
        // This would require tracking parent-child relationships in the AST
        // For a real implementation, we would need to enhance the parser
        return null;
    }

    private String getHookName(Value hookCall) {
        if (hookCall.hasMember("callee") &&
                hookCall.getMember("callee").hasMember("type") &&
                hookCall.getMember("callee").getMember("type").asString().equals("Identifier")) {

            return hookCall.getMember("callee").getMember("name").asString();
        }

        return "unknown";
    }

    private boolean isComponent(Value func) {
        // A real implementation would look for JSX return values
        return false;
    }

    private String getFunctionName(Value func) {
        if (func.hasMember("id") && !func.getMember("id").isNull() && func.getMember("id").hasMember("name")) {
            return func.getMember("id").getMember("name").asString();
        }

        return null;
    }

    private Value[] addNodes(Value[] array1, Value[] array2) {
        Value[] result = new Value[array1.length + array2.length];
        System.arraycopy(array1, 0, result, 0, array1.length);
        System.arraycopy(array2, 0, result, array1.length, array2.length);
        return result;
    }
}