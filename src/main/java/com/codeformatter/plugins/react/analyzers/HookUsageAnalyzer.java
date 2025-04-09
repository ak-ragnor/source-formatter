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

        _checkHookRules(ast, errors);

        if (enforceHookDependencies) {
            _checkHookDependencies(ast, errors);
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
                Value[] useEffectCalls = _findHookCalls(ast, "useEffect");
                Value[] useCallbackCalls = _findHookCalls(ast, "useCallback");
                Value[] useMemoCalls = _findHookCalls(ast, "useMemo");

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
    private void _checkHookRules(JsAst ast, List<FormatterError> errors) {
        Value[] functionComponents = _findFunctionComponents(ast);

        for (Value component : functionComponents) {
            _checkConditionalHooks(ast, component, errors);
            _checkHooksInLoops(ast, component, errors);
            _checkCustomHookNaming(ast, component, errors);
        }
    }

    /**
     * Check for conditional hook calls
     */
    private void _checkConditionalHooks(JsAst ast, Value component, List<FormatterError> errors) {
        Value[] hookCalls = _findAllHookCalls(ast, component);

        for (Value hookCall : hookCalls) {
            Value parent = _getParentNode(hookCall);
            if (parent != null) {
                String parentType = parent.getMember("type").asString();
                if (parentType.equals("IfStatement") ||
                        parentType.equals("ConditionalExpression") ||
                        parentType.equals("LogicalExpression")) {

                    errors.add(new FormatterError(
                            Severity.ERROR,
                            "React Hook '" + _getHookName(hookCall) + "' is called conditionally. " +
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
    private void _checkHooksInLoops(JsAst ast, Value component, List<FormatterError> errors) {
        Value[] hookCalls = _findAllHookCalls(ast, component);

        for (Value hookCall : hookCalls) {
            Value parent = _getParentNode(hookCall);
            if (parent != null) {
                String parentType = parent.getMember("type").asString();
                if (parentType.equals("ForStatement") ||
                        parentType.equals("ForInStatement") ||
                        parentType.equals("ForOfStatement") ||
                        parentType.equals("WhileStatement") ||
                        parentType.equals("DoWhileStatement")) {

                    errors.add(new FormatterError(
                            Severity.ERROR,
                            "React Hook '" + _getHookName(hookCall) + "' is called in a loop. " +
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
    private void _checkCustomHookNaming(JsAst ast, Value component, List<FormatterError> errors) {
        Value[] functions = ast.findNodes("FunctionDeclaration");
        functions = _addNodes(functions, ast.findNodes("ArrowFunctionExpression"));
        functions = _addNodes(functions, ast.findNodes("FunctionExpression"));

        for (Value func : functions) {
            if (_isComponent(func)) {
                continue;
            }

            Value[] hookCalls = _findAllHookCalls(ast, func);

            if (hookCalls.length > 0) {
                String funcName = _getFunctionName(func);

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
    private void _checkHookDependencies(JsAst ast, List<FormatterError> errors) {
        Value[] useEffectCalls = _findHookCalls(ast, "useEffect");
        _checkEffectDependencies(ast, useEffectCalls, errors);

        Value[] useCallbackCalls = _findHookCalls(ast, "useCallback");
        _checkCallbackDependencies(ast, useCallbackCalls, errors);

        Value[] useMemoCalls = _findHookCalls(ast, "useMemo");
        _checkMemoDependencies(ast, useMemoCalls, errors);
    }

    /**
     * Check useEffect dependencies
     */
    private void _checkEffectDependencies(JsAst ast, Value[] effectCalls, List<FormatterError> errors) {
        for (Value effect : effectCalls) {
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

            Value deps = depsArg.getMember("elements");
            if (deps.getArraySize() == 0) {
                // First argument should be a function
                Value callback = effect.getMember("arguments").getArrayElement(0);

                // TODO
                // Check if the callback uses variables outside its scope
                // This is a simplified check - a real implementation would be more sophisticated
            }
        }
    }

    /**
     * Check useCallback dependencies
     */
    private void _checkCallbackDependencies(JsAst ast, Value[] callbackCalls, List<FormatterError> errors) {
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
    private void _checkMemoDependencies(JsAst ast, Value[] memoCalls, List<FormatterError> errors) {
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

    private Value[] _findFunctionComponents(JsAst ast) {
        // TODO
        // This is a simplified implementation
        // A real implementation would need to look for functions that return JSX
        return ast.findNodes("FunctionDeclaration");
    }

    private Value[] _findHookCalls(JsAst ast, String hookName) {
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

    private Value[] _findAllHookCalls(JsAst ast, Value component) {
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

    private Value _getParentNode(Value node) {
        // TODO
        // This would require tracking parent-child relationships in the AST
        // For a real implementation, we would need to enhance the parser
        return null;
    }

    private String _getHookName(Value hookCall) {
        if (hookCall.hasMember("callee") &&
                hookCall.getMember("callee").hasMember("type") &&
                hookCall.getMember("callee").getMember("type").asString().equals("Identifier")) {

            return hookCall.getMember("callee").getMember("name").asString();
        }

        return "unknown";
    }

    private boolean _isComponent(Value func) {
        // TODO
        // A real implementation would look for JSX return values
        return false;
    }

    private String _getFunctionName(Value func) {
        if (func.hasMember("id") && !func.getMember("id").isNull() && func.getMember("id").hasMember("name")) {
            return func.getMember("id").getMember("name").asString();
        }

        return null;
    }

    private Value[] _addNodes(Value[] array1, Value[] array2) {
        Value[] result = new Value[array1.length + array2.length];
        System.arraycopy(array1, 0, result, 0, array1.length);
        System.arraycopy(array2, 0, result, array1.length, array2.length);
        return result;
    }
}