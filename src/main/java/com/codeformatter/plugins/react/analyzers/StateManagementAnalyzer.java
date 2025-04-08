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
import java.util.Map;

public class StateManagementAnalyzer implements ReactCodeAnalyzer {
    private final FormatterConfig config;
    private final JsEngine jsEngine;

    public StateManagementAnalyzer(FormatterConfig config, JsEngine jsEngine) {
        this.config = config;
        this.jsEngine = jsEngine;
    }

    @Override
    public ReactAnalyzerResult analyze(JsAst ast) {
        if (!ast.isValid()) {
            return new ReactAnalyzerResult(new ArrayList<>());
        }

        List<FormatterError> errors = new ArrayList<>();

        // Analyze state hooks
        analyzeStateHooks(ast, errors);

        // Analyze global state management
        analyzeGlobalState(ast, errors);

        // Analyze state derivation
        analyzeStateDependencies(ast, errors);

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

        // Apply state management improvements
        boolean improvedStateHooks = improveStateHooks(ast);
        if (improvedStateHooks) {
            refactorings.add(new Refactoring(
                    "STATE_HOOK_IMPROVEMENT",
                    1, // This would be more accurate with actual line numbers
                    1,
                    "Improved state hook usage patterns"
            ));
        }

        // Apply state derivation improvements
        boolean improvedStateDependencies = improveStateDependencies(ast);
        if (improvedStateDependencies) {
            refactorings.add(new Refactoring(
                    "STATE_DEPENDENCIES_IMPROVEMENT",
                    1, // This would be more accurate with actual line numbers
                    1,
                    "Improved state derivation and dependencies"
            ));
        }

        return new ReactRefactoringResult(refactorings, errors);
    }

    /**
     * Analyze useState and useReducer hook usage
     */
    private void analyzeStateHooks(JsAst ast, List<FormatterError> errors) {
        // Find all useState calls
        Value[] useStateCalls = findHookCalls(ast, "useState");

        // Check for excessive useState hooks in a single component
        Map<String, Integer> stateHooksByComponent = countHooksByComponent(ast, useStateCalls);

        for (Map.Entry<String, Integer> entry : stateHooksByComponent.entrySet()) {
            if (entry.getValue() > 5) {
                // Find the component node to get its location
                Value componentNode = findComponentByName(ast, entry.getKey());
                int line = componentNode != null ? ast.getNodeLine(componentNode) : 1;
                int column = componentNode != null ? ast.getNodeColumn(componentNode) : 1;

                errors.add(new FormatterError(
                        Severity.WARNING,
                        "Component '" + entry.getKey() + "' uses " + entry.getValue() +
                                " useState hooks which is excessive",
                        line, column,
                        "Consider using useReducer or consolidating related state variables"
                ));
            }
        }

        // Check for potential object state issues
        for (Value useStateCall : useStateCalls) {
            if (useStateCall.getMember("arguments").getArraySize() > 0) {
                Value initialState = useStateCall.getMember("arguments").getArrayElement(0);
                if (initialState.hasMember("type") &&
                        (initialState.getMember("type").asString().equals("ObjectExpression") ||
                                initialState.getMember("type").asString().equals("ArrayExpression"))) {

                    // Examine where the state setter is called
                    Value[] setterCalls = findSetterCalls(ast, useStateCall);
                    for (Value setterCall : setterCalls) {
                        if (isPartialUpdate(setterCall)) {
                            errors.add(new FormatterError(
                                    Severity.WARNING,
                                    "Potential object/array state update issue detected",
                                    ast.getNodeLine(setterCall),
                                    ast.getNodeColumn(setterCall),
                                    "Make sure to properly copy all properties when updating object/array state"
                            ));
                        }
                    }
                }
            }
        }
    }

    /**
     * Analyze global state management patterns
     */
    private void analyzeGlobalState(JsAst ast, List<FormatterError> errors) {
        // Look for common global state management patterns (Redux, Context, etc.)
        boolean usesRedux = hasReduxImports(ast);
        boolean usesContext = hasContextDefinitions(ast);
        boolean usesRecoil = hasRecoilImports(ast);

        // Count components using global state
        int reduxConnectedComponents = countReduxConnectedComponents(ast);

        // Check for inconsistent global state usage
        if (usesRedux && usesContext && !usesRecoil) {
            errors.add(new FormatterError(
                    Severity.INFO,
                    "Mixed usage of Redux and Context API detected",
                    1, 1,
                    "Consider standardizing on a single global state management approach"
            ));
        }

        // Check for excessive Redux connected components
        if (reduxConnectedComponents > 10) {
            errors.add(new FormatterError(
                    Severity.INFO,
                    "Excessive Redux connected components (" + reduxConnectedComponents + ")",
                    1, 1,
                    "Consider a more selective approach to connecting components to Redux"
            ));
        }
    }

    /**
     * Analyze dependencies between state variables
     */
    private void analyzeStateDependencies(JsAst ast, List<FormatterError> errors) {
        // Find useEffect calls that modify state
        Value[] useEffectCalls = findHookCalls(ast, "useEffect");

        for (Value effect : useEffectCalls) {
            // Check if the effect modifies state
            if (modifiesState(ast, effect)) {
                // Check if it has proper dependencies
                if (!hasProperDependencies(ast, effect)) {
                    errors.add(new FormatterError(
                            Severity.WARNING,
                            "useEffect modifies state but may be missing dependencies",
                            ast.getNodeLine(effect),
                            ast.getNodeColumn(effect),
                            "Add all dependencies or consider useMemo for derived state"
                    ));
                }
            }
        }

        // Check for derived state that should use useMemo
        checkDerivedState(ast, errors);
    }

    /**
     * Apply improvements to state hook usage
     */
    private boolean improveStateHooks(JsAst ast) {
        // In a real implementation, this would:
        // 1. Consolidate related state variables
        // 2. Convert complex state objects to useReducer
        // 3. Fix object/array state updates

        // For now, return false to indicate no changes
        return false;
    }

    /**
     * Apply improvements to state dependencies
     */
    private boolean improveStateDependencies(JsAst ast) {
        // In a real implementation, this would:
        // 1. Add missing dependencies to useEffect calls
        // 2. Convert derived state in useEffect to useMemo

        // For now, return false to indicate no changes
        return false;
    }

    /**
     * Helper method to find hook calls
     */
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

    /**
     * Count hook calls by component
     */
    private Map<String, Integer> countHooksByComponent(JsAst ast, Value[] hookCalls) {
        // This is a simplified implementation
        // In a real implementation, we would track which hooks belong to which component
        Map<String, Integer> result = new HashMap<>();
        result.put("Unknown", hookCalls.length);
        return result;
    }

    /**
     * Find a component by name
     */
    private Value findComponentByName(JsAst ast, String name) {
        // This is a simplified implementation
        // In a real implementation, we would find the component node by its name
        return null;
    }

    /**
     * Find calls to a state setter function
     */
    private Value[] findSetterCalls(JsAst ast, Value useStateCall) {
        // This is a simplified implementation
        // In a real implementation, we would track the setter variable and find all its calls
        return new Value[0];
    }

    /**
     * Check if a state setter call is a partial update of an object/array
     */
    private boolean isPartialUpdate(Value setterCall) {
        // This is a simplified implementation
        // In a real implementation, we would analyze the setter call argument
        return false;
    }

    /**
     * Check if the code imports Redux
     */
    private boolean hasReduxImports(JsAst ast) {
        Value[] imports = ast.findNodes("ImportDeclaration");

        for (Value importNode : imports) {
            String source = importNode.getMember("source").getMember("value").asString();
            if (source.contains("redux")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if the code defines React Contexts
     */
    private boolean hasContextDefinitions(JsAst ast) {
        Value[] calls = ast.findNodes("CallExpression");

        for (Value call : calls) {
            if (call.hasMember("callee") &&
                    call.getMember("callee").hasMember("property") &&
                    call.getMember("callee").getMember("property").hasMember("name") &&
                    call.getMember("callee").getMember("property").getMember("name").asString().equals("createContext")) {

                return true;
            }
        }

        return false;
    }

    /**
     * Check if the code imports Recoil
     */
    private boolean hasRecoilImports(JsAst ast) {
        Value[] imports = ast.findNodes("ImportDeclaration");

        for (Value importNode : imports) {
            String source = importNode.getMember("source").getMember("value").asString();
            if (source.equals("recoil")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Count components connected to Redux
     */
    private int countReduxConnectedComponents(JsAst ast) {
        // This is a simplified implementation
        // In a real implementation, we would count connect() or useSelector() calls
        return 0;
    }

    /**
     * Check if an effect modifies state
     */
    private boolean modifiesState(JsAst ast, Value effect) {
        // This is a simplified implementation
        // In a real implementation, we would check if the effect calls any state setters
        return false;
    }

    /**
     * Check if an effect has all required dependencies
     */
    private boolean hasProperDependencies(JsAst ast, Value effect) {
        // This is a simplified implementation
        // In a real implementation, we would analyze variables used in the effect
        return true;
    }

    /**
     * Check for derived state that should use useMemo
     */
    private void checkDerivedState(JsAst ast, List<FormatterError> errors) {
        // This is a simplified implementation
        // In a real implementation, we would look for state that's calculated from other state
    }
}