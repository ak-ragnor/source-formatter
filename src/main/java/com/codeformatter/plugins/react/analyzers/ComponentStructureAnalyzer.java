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

public class ComponentStructureAnalyzer implements ReactCodeAnalyzer {
    private final FormatterConfig config;
    private final JsEngine jsEngine;
    private final int maxComponentLines;

    public ComponentStructureAnalyzer(FormatterConfig config, JsEngine jsEngine) {
        this.config = config;
        this.jsEngine = jsEngine;
        this.maxComponentLines = config.getPluginConfig("react", "maxComponentLines", 150);
    }

    @Override
    public ReactAnalyzerResult analyze(JsAst ast) {
        if (!ast.isValid()) {
            return new ReactAnalyzerResult(new ArrayList<>());
        }

        List<FormatterError> errors = new ArrayList<>();
        List<Value> functionComponents = _findFunctionComponents(ast);
        List<Value> classComponents = _findClassComponents(ast);

        for (Value component : functionComponents) {
            _analyzeComponentStructure(ast, component, "function", errors);
        }

        for (Value component : classComponents) {
            _analyzeComponentStructure(ast, component, "class", errors);
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

        // TODO
        // Apply transformations to extract components
        // This is a complex operation that would require manipulating the AST
        // For this simplified implementation, we'll try to use the built-in transformation

        Map<String, Object> options = new HashMap<>();
        options.put("maxComponentLines", maxComponentLines);

        boolean success = jsEngine.transformAst(ast, "extractComponent", options);

        if (success) {
            List<Value> functionComponents = _findFunctionComponents(ast);

            if (!functionComponents.isEmpty()) {
                Value component = functionComponents.get(0);
                int startLine = ast.getNodeLine(component);
                int endLine = startLine + 30;

                refactorings.add(new Refactoring(
                        "COMPONENT_EXTRACTION",
                        startLine,
                        endLine,
                        "Extracted nested components from large components"
                ));
            } else {
                refactorings.add(new Refactoring(
                        "COMPONENT_EXTRACTION",
                        1, 1,
                        "Extracted nested components from large components"
                ));
            }
        } else {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Could not automatically extract components",
                    1, 1,
                    "Consider manually breaking down large components into smaller ones"
            ));
        }

        return new ReactRefactoringResult(refactorings, errors);
    }

    private List<Value> _findFunctionComponents(JsAst ast) {
        List<Value> components = new ArrayList<>();

        Value[] functionDeclarations = ast.findNodes("FunctionDeclaration");
        for (Value func : functionDeclarations) {
            if (_isFunctionComponent(ast, func)) {
                components.add(func);
            }
        }

        Value[] arrowFunctions = ast.findNodes("ArrowFunctionExpression");
        for (Value arrow : arrowFunctions) {
            if (_isFunctionComponent(ast, arrow)) {
                components.add(arrow);
            }
        }

        return components;
    }

    private List<Value> _findClassComponents(JsAst ast) {
        List<Value> components = new ArrayList<>();

        Value[] classDeclarations = ast.findNodes("ClassDeclaration");
        for (Value cls : classDeclarations) {
            if (_isClassComponent(ast, cls)) {
                components.add(cls);
            }
        }

        return components;
    }

    private boolean _isFunctionComponent(JsAst ast, Value node) {

        if (node.hasMember("id") && !node.getMember("id").isNull()) {
            String name = ast.getStringProperty(node.getMember("id"), "name");

            if (name.isEmpty() || !Character.isUpperCase(name.charAt(0))) {
                return false;
            }
        }

        if (node.hasMember("body")) {
            Value body = node.getMember("body");

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

    private boolean _isClassComponent(JsAst ast, Value node) {
        if (!node.hasMember("superClass")) {
            return false;
        }

        Value superClass = node.getMember("superClass");
        if (superClass.isNull()) {
            return false;
        }

        if (superClass.hasMember("type") && superClass.getMember("type").asString().equals("Identifier")) {
            String name = ast.getStringProperty(superClass, "name");
            if (name.equals("Component")) {
                return true;
            }
        }

        if (superClass.hasMember("type") && superClass.getMember("type").asString().equals("MemberExpression")) {
            Value object = superClass.getMember("object");
            Value property = superClass.getMember("property");

            if (object.hasMember("type") && object.getMember("type").asString().equals("Identifier") &&
                    property.hasMember("type") && property.getMember("type").asString().equals("Identifier")) {

                String objName = ast.getStringProperty(object, "name");
                String propName = ast.getStringProperty(property, "name");

                if (objName.equals("React") && propName.equals("Component")) {
                    return true;
                }
            }
        }

        return false;
    }

    private void _analyzeComponentStructure(JsAst ast, Value component, String componentType, List<FormatterError> errors) {
        String componentName = "Unknown";
        if (component.hasMember("id") && !component.getMember("id").isNull()) {
            componentName = ast.getStringProperty(component.getMember("id"), "name");
        } else if (componentType.equals("function") && component.hasMember("parent") && !component.getMember("parent").isNull()) {
            Value parent = component.getMember("parent");
            if (parent.hasMember("id") && !parent.getMember("id").isNull()) {
                componentName = ast.getStringProperty(parent.getMember("id"), "name");
            }
        }

        int componentSize = _estimateComponentSize(ast, component);
        if (componentSize > maxComponentLines) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Component '" + componentName + "' exceeds recommended size of " + maxComponentLines + " lines (estimated " + componentSize + " lines)",
                    ast.getNodeLine(component),
                    ast.getNodeColumn(component),
                    "Consider breaking this component into smaller subcomponents"
            ));
        }

        int propCount = _countProps(component);
        if (propCount > 10) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Component '" + componentName + "' has too many props (" + propCount + ")",
                    ast.getNodeLine(component),
                    ast.getNodeColumn(component),
                    "Consider grouping related props or breaking down the component"
            ));
        }

        int maxNestingDepth = _findMaxJsxNestingDepth(ast, component);
        if (maxNestingDepth > 4) {
            errors.add(new FormatterError(
                    Severity.WARNING,
                    "Component '" + componentName + "' has deeply nested JSX (depth " + maxNestingDepth + ")",
                    ast.getNodeLine(component),
                    ast.getNodeColumn(component),
                    "Consider extracting nested JSX into separate components"
            ));
        }

        if (componentType.equals("class")) {
            boolean hasComplexLifecycles = _checkLifecycleComplexity(ast, component);
            if (hasComplexLifecycles) {
                errors.add(new FormatterError(
                        Severity.WARNING,
                        "Component '" + componentName + "' has complex lifecycle methods",
                        ast.getNodeLine(component),
                        ast.getNodeColumn(component),
                        "Consider simplifying lifecycle methods or using React hooks in a functional component"
                ));
            }
        }
    }

    private int _estimateComponentSize(JsAst ast, Value component) {
        // TODO
        // For a more accurate size, we would need to look at the original source code
        // This is just a rough estimation based on the AST structure

        if (component.hasMember("loc") &&
                component.getMember("loc").hasMember("start") &&
                component.getMember("loc").hasMember("end")) {

            int startLine = component.getMember("loc").getMember("start").getMember("line").asInt();
            int endLine = component.getMember("loc").getMember("end").getMember("line").asInt();

            return endLine - startLine + 1;
        }

        if (component.hasMember("body")) {
            Value body = component.getMember("body");

            if (body.hasMember("body") && body.getMember("body").hasArrayElements()) {
                return (int)body.getMember("body").getArraySize() * 3;
            }
        }

        return 20; // default fallback estimate
    }

    private int _countProps(Value component) {
        if (component.hasMember("params") && component.getMember("params").hasArrayElements()) {
            if (component.getMember("params").getArraySize() > 0) {
                Value firstParam = component.getMember("params").getArrayElement(0);

                if (firstParam.hasMember("type") &&
                        firstParam.getMember("type").asString().equals("ObjectPattern")) {

                    if (firstParam.hasMember("properties") &&
                            firstParam.getMember("properties").hasArrayElements()) {

                        return (int)firstParam.getMember("properties").getArraySize();
                    }
                }
            }

            return (int)component.getMember("params").getArraySize();
        }

        // TODO
        // For class components, we would need to look for propTypes or defaultProps
        // This is a simplified implementation
        return 0;
    }

    private int _findMaxJsxNestingDepth(JsAst ast, Value component) {
        // TODO
        // Find JSX in the component body
        // For a complete implementation, we would need to traverse the AST
        // This is a simplified version that just checks for JSXElements

        Value[] jsxElements = ast.findNodes("JSXElement");

        int maxDepth = 0;

        for (Value jsx : jsxElements) {
            int depth = _calculateJsxDepth(jsx);
            if (depth > maxDepth) {
                maxDepth = depth;
            }
        }

        return maxDepth;
    }

    private int _calculateJsxDepth(Value jsxElement) {
        if (!jsxElement.hasMember("children") ||
                !jsxElement.getMember("children").hasArrayElements()) {
            return 1;
        }

        int maxChildDepth = 0;
        for (int i = 0; i < jsxElement.getMember("children").getArraySize(); i++) {
            Value child = jsxElement.getMember("children").getArrayElement(i);

            if (child.hasMember("type") &&
                    child.getMember("type").asString().equals("JSXElement")) {

                int childDepth = _calculateJsxDepth(child);
                if (childDepth > maxChildDepth) {
                    maxChildDepth = childDepth;
                }
            }
        }

        return 1 + maxChildDepth;
    }

    private boolean _checkLifecycleComplexity(JsAst ast, Value classComponent) {
        if (!classComponent.hasMember("body") ||
                !classComponent.getMember("body").hasMember("body")) {
            return false;
        }

        Value classBody = classComponent.getMember("body").getMember("body");
        if (!classBody.hasArrayElements()) {
            return false;
        }

        List<String> lifecycleMethods = List.of(
                "componentDidMount",
                "componentDidUpdate",
                "componentWillUnmount",
                "shouldComponentUpdate",
                "getSnapshotBeforeUpdate",
                "getDerivedStateFromProps",
                "componentWillReceiveProps"
        );

        int complexLifecycleCount = 0;

        for (int i = 0; i < classBody.getArraySize(); i++) {
            Value node = classBody.getArrayElement(i);

            if (node.hasMember("type") &&
                    node.getMember("type").asString().equals("ClassMethod")) {

                if (node.hasMember("key") &&
                        node.getMember("key").hasMember("name")) {

                    String methodName = node.getMember("key").getMember("name").asString();

                    if (lifecycleMethods.contains(methodName)) {
                        if (_isMethodComplex(node)) {
                            complexLifecycleCount++;
                        }
                    }
                }
            }
        }

        return complexLifecycleCount > 0;
    }

    private boolean _isMethodComplex(Value method) {
        if (!method.hasMember("body") ||
                !method.getMember("body").hasMember("body")) {
            return false;
        }

        Value body = method.getMember("body").getMember("body");
        if (!body.hasArrayElements()) {
            return false;
        }

        if (body.getArraySize() > 10) {
            return true;
        }

        for (int i = 0; i < body.getArraySize(); i++) {
            Value stmt = body.getArrayElement(i);

            if (stmt.hasMember("type")) {
                String type = stmt.getMember("type").asString();

                if (type.equals("IfStatement") ||
                        type.equals("SwitchStatement") ||
                        type.equals("ForStatement") ||
                        type.equals("WhileStatement") ||
                        type.equals("DoWhileStatement")) {

                    return true;
                }
            }
        }

        return false;
    }
}