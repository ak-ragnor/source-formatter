package com.codeformatter.plugins.react.analyzers;

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
import java.util.List;

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

        // Find all useState calls
        Value[] useStateCalls = findHookCalls(ast, "useState");

        // Check for excessive useState hooks
        if (useStateCalls.length > 5) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Component uses " + useStateCalls.length + " useState hooks which may be excessive",
                    1, 1,
                    "Consider using useReducer or consolidating related state variables"
            ));
        }

        // Check for potential object state issues
        for (Value useStateCall : useStateCalls) {
            if (useStateCall.getMember("arguments").getArraySize() > 0) {
                Value initialState = useStateCall.getMember("arguments").getArrayElement(0);
                if (initialState.hasMember("type") &&
                        (initialState.getMember("type").asString().equals("ObjectExpression") ||
                                initialState.getMember("type").asString().equals("ArrayExpression"))) {

                    errors.add(new FormatterError(
                            Severity.INFO,
                            "Object/array state detected which may need careful updates",
                            ast.getNodeLine(useStateCall),
                            ast.getNodeColumn(useStateCall),
                            "Remember to properly copy all properties when updating object/array state"
                    ));
                }
            }
        }

        // Find useEffect calls that modify state
        Value[] useEffectCalls = findHookCalls(ast, "useEffect");
        for (Value effect : useEffectCalls) {
            // Check for empty dependency array (problematic if effect has side effects)
            if (effect.getMember("arguments").getArraySize() > 1) {
                Value depsArg = effect.getMember("arguments").getArrayElement(1);
                if (depsArg.hasMember("type") &&
                        depsArg.getMember("type").asString().equals("ArrayExpression") &&
                        depsArg.getMember("elements").getArraySize() == 0) {

                    errors.add(new FormatterError(
                            Severity.WARNING,
                            "useEffect with empty dependency array should not have side effects",
                            ast.getNodeLine(effect),
                            ast.getNodeColumn(effect),
                            "Check if this effect should depend on any props or state"
                    ));
                }
            }
        }

        return new ReactAnalyzerResult(errors);
    }

    @Override
    public boolean canAutoFix() {
        return false;
    }

    @Override
    public ReactRefactoringResult applyRefactoring(JsAst ast) {

        return new ReactRefactoringResult(new ArrayList<>(), new ArrayList<>());
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
}