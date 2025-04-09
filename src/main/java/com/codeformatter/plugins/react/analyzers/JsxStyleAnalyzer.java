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

public class JsxStyleAnalyzer implements ReactCodeAnalyzer {
    private final FormatterConfig config;
    private final JsEngine jsEngine;
    private final String jsxLineBreakRule;

    public JsxStyleAnalyzer(FormatterConfig config, JsEngine jsEngine) {
        this.config = config;
        this.jsEngine = jsEngine;
        this.jsxLineBreakRule = config.getPluginConfig("react", "jsxLineBreakRule", "multiline");
    }

    @Override
    public ReactAnalyzerResult analyze(JsAst ast) {
        if (!ast.isValid()) {
            return new ReactAnalyzerResult(new ArrayList<>());
        }

        Value[] jsxElements = ast.findNodes("JSXElement");

        List<FormatterError> errors = new ArrayList<>();

        for (Value jsxElement : jsxElements) {
            _checkInlineStyles(ast, jsxElement, errors);
            _checkLineBreaks(ast, jsxElement, errors);
            _checkEmptyFragments(ast, jsxElement, errors);
            _checkExcessiveProps(ast, jsxElement, errors);
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

        Map<String, Object> options = new HashMap<>();
        options.put("lineBreakRule", jsxLineBreakRule);

        boolean success = jsEngine.transformAst(ast, "improveJsxStyle", options);

        if (success) {
            refactorings.add(new Refactoring(
                    "JSX_STYLE_IMPROVEMENT",
                    1,
                    1,
                    "Improved JSX formatting and style according to best practices"
            ));
        } else {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Could not automatically improve JSX style",
                    1, 1,
                    "Consider manually reviewing JSX style issues"
            ));
        }

        return new ReactRefactoringResult(refactorings, errors);
    }

    /**
     * Check for inline style usage in JSX
     */
    private void _checkInlineStyles(JsAst ast, Value jsxElement, List<FormatterError> errors) {
        Value[] attributes = _getJSXAttributes(jsxElement);

        for (Value attr : attributes) {
            String attrName = ast.getStringProperty(attr.getMember("name"), "name");

            if ("style".equals(attrName)) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "Inline styles detected in JSX element",
                        ast.getNodeLine(attr),
                        ast.getNodeColumn(attr),
                        "Consider using CSS classes or styled components instead of inline styles"
                ));
            }
        }
    }

    /**
     * Check for proper line breaks in JSX components with many props or children
     */
    private void _checkLineBreaks(JsAst ast, Value jsxElement, List<FormatterError> errors) {
        Value[] attributes = _getJSXAttributes(jsxElement);
        Value[] children = _getJSXChildren(jsxElement);

        if (attributes.length > 3) {
            if ("multiline".equals(jsxLineBreakRule)) {
                errors.add(new FormatterError(
                        Severity.INFO,
                        "JSX element with many attributes may be hard to read",
                        ast.getNodeLine(jsxElement),
                        ast.getNodeColumn(jsxElement),
                        "Consider breaking attributes into multiple lines for better readability"
                ));
            }
        }

        if (children.length > 3) {
            // Again, in a real implementation we would check the actual line breaks
            errors.add(new FormatterError(
                    Severity.INFO,
                    "JSX element with many children may be hard to read",
                    ast.getNodeLine(jsxElement),
                    ast.getNodeColumn(jsxElement),
                    "Consider breaking content into multiple lines for better readability"
            ));
        }
    }

    /**
     * Check for empty fragments that could be simplified or removed
     */
    private void _checkEmptyFragments(JsAst ast, Value jsxElement, List<FormatterError> errors) {
        String openingElement = ast.getStringProperty(jsxElement.getMember("openingElement").getMember("name"), "name");

        if ("Fragment".equals(openingElement) || "React.Fragment".equals(openingElement)) {
            Value[] children = _getJSXChildren(jsxElement);

            if (children.length == 0) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "Empty React Fragment detected",
                        ast.getNodeLine(jsxElement),
                        ast.getNodeColumn(jsxElement),
                        "Consider removing the unnecessary Fragment"
                ));
            } else if (children.length == 1) {
                errors.add(new FormatterError(
                        Severity.INFO,
                        "React Fragment with single child could be simplified",
                        ast.getNodeLine(jsxElement),
                        ast.getNodeColumn(jsxElement),
                        "Consider removing the Fragment and just using the single child"
                ));
            }
        }
    }

    /**
     * Check for components with excessive props that might need refactoring
     */
    private void _checkExcessiveProps(JsAst ast, Value jsxElement, List<FormatterError> errors) {
        Value[] attributes = _getJSXAttributes(jsxElement);

        if (attributes.length > 7) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "JSX element has " + attributes.length + " props which is excessive",
                    ast.getNodeLine(jsxElement),
                    ast.getNodeColumn(jsxElement),
                    "Consider breaking this component into smaller components or using composition"
            ));
        }
    }

    /**
     * Helper method to get JSX attributes from an element
     */
    private Value[] _getJSXAttributes(Value jsxElement) {
        if (jsxElement.hasMember("openingElement") &&
                jsxElement.getMember("openingElement").hasMember("attributes")) {

            Value attributes = jsxElement.getMember("openingElement").getMember("attributes");
            int size = (int) attributes.getArraySize();
            Value[] result = new Value[size];

            for (int i = 0; i < size; i++) {
                result[i] = attributes.getArrayElement(i);
            }

            return result;
        }

        return new Value[0];
    }

    /**
     * Helper method to get JSX children from an element
     */
    private Value[] _getJSXChildren(Value jsxElement) {
        if (jsxElement.hasMember("children")) {
            Value children = jsxElement.getMember("children");
            int size = (int) children.getArraySize();
            Value[] result = new Value[size];

            for (int i = 0; i < size; i++) {
                result[i] = children.getArrayElement(i);
            }

            return result;
        }

        return new Value[0];
    }
}