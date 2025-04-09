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

import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Analyzes React Hook usage patterns and detects common issues
 */
public class HookUsageAnalyzer implements ReactCodeAnalyzer {
    private static final Logger logger = Logger.getLogger(HookUsageAnalyzer.class.getName());

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
        JsAst.AstNodeFinder nodeFinder = new JsAst.AstNodeFinder(ast);

        _checkHookRules(ast, nodeFinder, errors);

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
                            1, 1,
                            "Fixed dependency arrays in " + totalHooks + " React hooks"
                    ));
                }
            } else {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "Could not automatically fix hook dependencies",
                        1, 1,
                        "Check your hook dependencies manually to ensure they include all required values"
                ));
            }
        }

        return new ReactRefactoringResult(refactorings, errors);
    }

    /**
     * Check if React Hook Rules are being followed
     */
    private void _checkHookRules(JsAst ast, JsAst.AstNodeFinder nodeFinder, List<FormatterError> errors) {
        Value[] hookCalls = _findAllHookCalls(ast);

        for (Value hookCall : hookCalls) {
            if (nodeFinder.isInConditional(hookCall)) {
                errors.add(new FormatterError(
                        Severity.ERROR,
                        "React Hook '" + _getHookName(hookCall) + "' is called conditionally. " +
                                "Hooks must be called at the top level of your component.",
                        ast.getNodeLine(hookCall),
                        ast.getNodeColumn(hookCall),
                        "Move this hook outside of conditions"
                ));
            }

            if (nodeFinder.isInLoop(hookCall)) {
                errors.add(new FormatterError(
                        Severity.ERROR,
                        "React Hook '" + _getHookName(hookCall) + "' is called in a loop. " +
                                "Hooks must be called at the top level of your component.",
                        ast.getNodeLine(hookCall),
                        ast.getNodeColumn(hookCall),
                        "Move this hook outside of the loop"
                ));
            }

            if (!_isInComponentOrHook(ast, nodeFinder, hookCall)) {
                errors.add(new FormatterError(
                        Severity.ERROR,
                        "React Hook '" + _getHookName(hookCall) + "' is called in a regular function. " +
                                "Hooks must be called in a React function component or a custom React Hook function.",
                        ast.getNodeLine(hookCall),
                        ast.getNodeColumn(hookCall),
                        "Move this hook to a component function or custom hook"
                ));
            }
        }

        Value[] functions = ast.findNodes("FunctionDeclaration");
        for (Value func : functions) {
            if (_isComponent(ast, func)) {
                continue;
            }

            Value[] hooksInFunction = _findHooksInFunction(ast, nodeFinder, func);
            if (hooksInFunction.length > 0) {
                String funcName = _getFunctionName(ast, func);
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
     * Find all hooks called within a specific function
     */
    private Value[] _findHooksInFunction(JsAst ast, JsAst.AstNodeFinder nodeFinder, Value func) {
        List<Value> hooksInFunction = new ArrayList<>();
        Value[] allHooks = _findAllHookCalls(ast);

        for (Value hook : allHooks) {
            Value parent = nodeFinder.findParentOfType(hook, "FunctionDeclaration");
            if (parent == func) {
                hooksInFunction.add(hook);
            }

            Value arrowParent = nodeFinder.findParentOfType(hook, "ArrowFunctionExpression");
            if (arrowParent == func) {
                hooksInFunction.add(hook);
            }
        }

        return hooksInFunction.toArray(new Value[0]);
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

                Value callback = effect.getMember("arguments").getArrayElement(0);
                if (callback.hasMember("body") && callback.getMember("body").hasMember("body")) {

                    boolean usesExternalVars = _doesEffectUseExternalVars(ast, callback);
                    if (usesExternalVars) {
                        errors.add(new FormatterError(
                                Severity.WARNING,
                                "useEffect has empty dependency array but appears to use props, state, or other variables",
                                ast.getNodeLine(effect),
                                ast.getNodeColumn(effect),
                                "Add all variables used in the effect to the dependency array, or refactor to avoid dependencies"
                        ));
                    }
                }
            }
        }
    }

    /**
     * Check useCallback dependencies
     */
    private void _checkCallbackDependencies(JsAst ast, Value[] callbackCalls, List<FormatterError> errors) {
        for (Value callback : callbackCalls) {
            if (callback.getMember("arguments").getArraySize() < 2) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "useCallback is missing dependency array",
                        ast.getNodeLine(callback),
                        ast.getNodeColumn(callback),
                        "Add a dependency array as the second argument"
                ));
                continue;
            }

            Value depsArg = callback.getMember("arguments").getArrayElement(1);
            if (!depsArg.getMember("type").asString().equals("ArrayExpression")) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "useCallback second argument should be an array",
                        ast.getNodeLine(depsArg),
                        ast.getNodeColumn(depsArg),
                        "Replace with an array of dependencies"
                ));
                continue;
            }

            Value callbackFunc = callback.getMember("arguments").getArrayElement(0);
            Value deps = depsArg.getMember("elements");

            if (deps.getArraySize() == 0 && _doesFunctionUseExternalVars(ast, callbackFunc)) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "useCallback has empty dependency array but function uses variables from its scope",
                        ast.getNodeLine(callback),
                        ast.getNodeColumn(callback),
                        "Add dependencies for all variables used in the function"
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
                continue;
            }

            Value depsArg = memo.getMember("arguments").getArrayElement(1);
            if (!depsArg.getMember("type").asString().equals("ArrayExpression")) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "useMemo second argument should be an array",
                        ast.getNodeLine(depsArg),
                        ast.getNodeColumn(depsArg),
                        "Replace with an array of dependencies"
                ));
                continue;
            }

            Value memoFunc = memo.getMember("arguments").getArrayElement(0);
            Value deps = depsArg.getMember("elements");

            if (deps.getArraySize() == 0 && _doesFunctionUseExternalVars(ast, memoFunc)) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "useMemo has empty dependency array but computation uses variables from its scope",
                        ast.getNodeLine(memo),
                        ast.getNodeColumn(memo),
                        "Add dependencies for all variables used in the computation"
                ));
            }
        }
    }

    /**
     * Find React hook calls of a specific type
     */
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

    /**
     * Find all React hook calls
     */
    private Value[] _findAllHookCalls(JsAst ast) {
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

    /**
     * Get the name of the hook being called
     */
    private String _getHookName(Value hookCall) {
        if (hookCall.hasMember("callee") &&
                hookCall.getMember("callee").hasMember("type") &&
                hookCall.getMember("callee").getMember("type").asString().equals("Identifier")) {

            return hookCall.getMember("callee").getMember("name").asString();
        }

        return "unknown";
    }

    /**
     * Check if a node is inside a React component or custom hook
     */
    private boolean _isInComponentOrHook(JsAst ast, JsAst.AstNodeFinder nodeFinder, Value node) {
        Value functionDecl = nodeFinder.findParentOfType(node, "FunctionDeclaration");
        if (functionDecl != null) {
            if (_isComponent(ast, functionDecl)) {
                return true;
            }

            String funcName = _getFunctionName(ast, functionDecl);
            return funcName != null && funcName.startsWith("use");
        }

        Value arrowFunc = nodeFinder.findParentOfType(node, "ArrowFunctionExpression");
        if (arrowFunc != null) {

            if (_returnsJSX(ast, arrowFunc)) {
                return true;
            }

            Value varDecl = nodeFinder.findParentOfType(arrowFunc, "VariableDeclarator");
            if (varDecl != null && varDecl.hasMember("id") && varDecl.getMember("id").hasMember("name")) {
                String varName = varDecl.getMember("id").getMember("name").asString();
                return varName.startsWith("use");
            }
        }

        return false;
    }

    /**
     * Check if a function is a React component
     */
    private boolean _isComponent(JsAst ast, Value func) {
        String name = _getFunctionName(ast, func);
        if (name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
            return true;
        }

        return _returnsJSX(ast, func);
    }

    /**
     * Check if a function returns JSX
     */
    private boolean _returnsJSX(JsAst ast, Value func) {
        if (func.hasMember("body")) {
            Value body = func.getMember("body");

            if (body.hasMember("type") &&
                    (body.getMember("type").asString().equals("JSXElement") ||
                            body.getMember("type").asString().equals("JSXFragment"))) {
                return true;
            }

            if (body.hasMember("body") && body.getMember("body").hasArrayElements()) {
                for (int i = 0; i < body.getMember("body").getArraySize(); i++) {
                    Value stmt = body.getMember("body").getArrayElement(i);

                    if (stmt.hasMember("type") && stmt.getMember("type").asString().equals("ReturnStatement")) {
                        Value returnArg = stmt.getMember("argument");
                        if (returnArg != null && !returnArg.isNull() && returnArg.hasMember("type")) {
                            String returnType = returnArg.getMember("type").asString();
                            if (returnType.equals("JSXElement") || returnType.equals("JSXFragment")) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Get function name from function declaration or expression
     */
    private String _getFunctionName(JsAst ast, Value func) {
        if (func.hasMember("id") && !func.getMember("id").isNull() && func.getMember("id").hasMember("name")) {
            return func.getMember("id").getMember("name").asString();
        }

        return null;
    }

    /**
     * Check if an effect uses variables from outside its scope
     */
    private boolean _doesEffectUseExternalVars(JsAst ast, Value effectCallback) {
        try {
            // TODO
            // This is a simplified implementation
            // A complete implementation would need to analyze scope

            Value body = effectCallback.getMember("body");
            if (body.hasMember("body") && body.getMember("body").hasArrayElements()) {
                for (int i = 0; i < body.getMember("body").getArraySize(); i++) {
                    Value stmt = body.getMember("body").getArrayElement(i);

                    Value[] calls = _findCallsInNode(ast, stmt);
                    for (Value call : calls) {
                        if (call.hasMember("callee") && call.getMember("callee").hasMember("type") &&
                                call.getMember("callee").getMember("type").asString().equals("Identifier")) {

                            String calleeName = call.getMember("callee").getMember("name").asString();
                            if (calleeName.equals("setState") || calleeName.startsWith("set")) {
                                return true;
                            }
                        }
                    }

                    Value[] identifiers = _findIdentifiersInNode(ast, stmt);
                    for (Value id : identifiers) {
                        String name = id.getMember("name").asString();
                        if (!_isLocalVariable(effectCallback, name) &&
                                !_isGlobalVariable(name) &&
                                !name.equals("props")) {
                            return true;
                        }
                    }
                }
            }

            return false;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error analyzing effect dependencies", e);
            return false;
        }
    }

    /**
     * Check if a function uses variables from outside its scope
     */
    private boolean _doesFunctionUseExternalVars(JsAst ast, Value func) {
        try {
            // TODO
            // This is a simplified implementation
            // A complete implementation would perform proper scope analysis
            return true; // To be safe, assume it uses external vars
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error analyzing function dependencies", e);
            return false;
        }
    }

    /**
     * Find all function calls within a node
     */
    private Value[] _findCallsInNode(JsAst ast, Value node) {
        try {
            List<Value> calls = new ArrayList<>();

            // TODO
            // This is a limited implementation
            // A complete implementation would need to traverse the AST
            if (node.hasMember("type") && node.getMember("type").asString().equals("CallExpression")) {
                calls.add(node);
            }

            if (node.hasMember("expression") && !node.getMember("expression").isNull()) {
                Value[] childCalls = _findCallsInNode(ast, node.getMember("expression"));
                calls.addAll(Arrays.asList(childCalls));
            }

            return calls.toArray(new Value[0]);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error finding calls in node", e);
            return new Value[0];
        }
    }

    /**
     * Find all identifiers within a node
     */
    private Value[] _findIdentifiersInNode(JsAst ast, Value node) {
        try {
            List<Value> identifiers = new ArrayList<>();

            // TODO
            // This is a limited implementation
            // A complete implementation would need to traverse the AST

            if (node.hasMember("type") && node.getMember("type").asString().equals("Identifier")) {
                identifiers.add(node);
            }

            return identifiers.toArray(new Value[0]);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error finding identifiers in node", e);
            return new Value[0];
        }
    }

    /**
     * Check if a variable is defined locally in a function
     */
    private boolean _isLocalVariable(Value func, String name) {
        try {
            if (func.hasMember("params") && func.getMember("params").hasArrayElements()) {
                for (int i = 0; i < func.getMember("params").getArraySize(); i++) {
                    Value param = func.getMember("params").getArrayElement(i);
                    if (param.hasMember("name") && param.getMember("name").asString().equals(name)) {
                        return true;
                    }
                }
            }

            Value body = func.getMember("body");
            if (body.hasMember("body") && body.getMember("body").hasArrayElements()) {
                for (int i = 0; i < body.getMember("body").getArraySize(); i++) {
                    Value stmt = body.getMember("body").getArrayElement(i);

                    if (stmt.hasMember("type") && stmt.getMember("type").asString().equals("VariableDeclaration")) {
                        Value declarations = stmt.getMember("declarations");
                        for (int j = 0; j < declarations.getArraySize(); j++) {
                            Value decl = declarations.getArrayElement(j);
                            if (decl.hasMember("id") && decl.getMember("id").hasMember("name") &&
                                    decl.getMember("id").getMember("name").asString().equals(name)) {
                                return true;
                            }
                        }
                    }
                }
            }

            return false;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error checking local variable", e);
            return false;
        }
    }

    /**
     * Check if a variable is a global/built-in
     */
    private boolean _isGlobalVariable(String name) {
        List<String> globals = List.of(
                "window", "document", "console", "setTimeout", "setInterval",
                "clearTimeout", "clearInterval", "fetch", "Promise", "JSON",
                "Math", "Date", "Array", "Object", "undefined", "null", "NaN"
        );

        return globals.contains(name);
    }
}