package com.codeformatter.plugins.react;

import org.graalvm.polyglot.Value;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a JavaScript/React Abstract Syntax Tree.
 * This class acts as a bridge between Java and the JavaScript AST produced by Babel.
 */
public class JsAst {
    private final String sourceCode;
    private final boolean valid;
    private final String error;
    private final Value astValue;

    public JsAst(String sourceCode) {
        this(sourceCode, true, null, null);
    }

    public JsAst(String sourceCode, boolean valid, String error) {
        this(sourceCode, valid, error, null);
    }

    public JsAst(String sourceCode, boolean valid, String error, Value astValue) {
        this.sourceCode = sourceCode;
        this.valid = valid;
        this.error = error;
        this.astValue = astValue;
    }

    /**
     * Get the original source code
     */
    public String getSourceCode() {
        return sourceCode;
    }

    /**
     * Check if the AST is valid
     */
    public boolean isValid() {
        return valid;
    }

    /**
     * Get error message if parsing failed
     */
    public String getError() {
        return error;
    }

    /**
     * Get the underlying AST value
     */
    public Value getAstValue() {
        return astValue;
    }

    /**
     * Find all nodes of a specific type
     */
    public Value[] findNodes(String nodeType) {
        if (!isValid() || astValue == null) {
            return new Value[0];
        }

        try {
            Value result = astValue.getMember("findNodes").execute(nodeType);
            int size = (int) result.getArraySize();
            Value[] nodes = new Value[size];

            for (int i = 0; i < size; i++) {
                nodes[i] = result.getArrayElement(i);
            }

            return nodes;
        } catch (Exception e) {
            return new Value[0];
        }
    }

    /**
     * Find the line number for a node
     */
    public int getNodeLine(Value node) {
        if (node == null || !node.hasMember("loc")) {
            return 1;
        }

        try {
            return node.getMember("loc").getMember("start").getMember("line").asInt();
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Find the column number for a node
     */
    public int getNodeColumn(Value node) {
        if (node == null || !node.hasMember("loc")) {
            return 1;
        }

        try {
            return node.getMember("loc").getMember("start").getMember("column").asInt();
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Get a string property from a node
     */
    public String getStringProperty(Value node, String property) {
        if (node == null || !node.hasMember(property)) {
            return "";
        }

        try {
            Value prop = node.getMember(property);
            return prop.isString() ? prop.asString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Enhanced utility class for traversing AST nodes and finding parent nodes
     * This can be used by analyzers when parent node information is needed
     */
    public static class AstNodeFinder {
        private final JsAst ast;
        private final Map<Value, Value> parentMap = new HashMap<>();
        private boolean traversed = false;

        public AstNodeFinder(JsAst ast) {
            this.ast = ast;
        }

        /**
         * Gets the parent node of a given node
         * Lazily builds the parent map on first use
         */
        public Value getParentNode(Value node) {
            if (!traversed) {
                buildParentMap();
            }
            return parentMap.get(node);
        }

        /**
         * Traverses the AST once to build a map of parent-child relationships
         * This is more efficient than traversing for each lookup
         */
        private void buildParentMap() {
            if (!ast.isValid() || ast.getAstValue() == null) {
                traversed = true;
                return;
            }

            try {
                // Add a JavaScript traversal function to build the parent map
                ast.getAstValue().getMember("context").invokeMember("eval",
                        "function buildParentMap(ast) {\n" +
                                "  const map = new Map();\n" +
                                "  \n" +
                                "  function visit(node, parent) {\n" +
                                "    if (node && typeof node === 'object') {\n" +
                                "      if (parent) map.set(node, parent);\n" +
                                "      \n" +
                                "      for (const key in node) {\n" +
                                "        const child = node[key];\n" +
                                "        \n" +
                                "        if (Array.isArray(child)) {\n" +
                                "          for (const item of child) {\n" +
                                "            visit(item, node);\n" +
                                "          }\n" +
                                "        } else if (child && typeof child === 'object') {\n" +
                                "          visit(child, node);\n" +
                                "        }\n" +
                                "      }\n" +
                                "    }\n" +
                                "  }\n" +
                                "  \n" +
                                "  visit(ast, null);\n" +
                                "  return map;\n" +
                                "}\n");

                // Call the function to build the parent map
                Value jsParentMap = ast.getAstValue().getMember("context").invokeMember(
                        "eval", "buildParentMap(currentAst)");

                // Convert the JavaScript Map to our Java Map
                Value keys = jsParentMap.invokeMember("keys");
                Value keysIterator = keys.invokeMember("next");

                while (!keysIterator.getMember("done").asBoolean()) {
                    Value key = keysIterator.getMember("value");
                    Value parent = jsParentMap.invokeMember("get", key);

                    parentMap.put(key, parent);

                    keysIterator = keys.invokeMember("next");
                }

                traversed = true;
            } catch (Exception e) {
                traversed = true;
            }
        }

        /**
         * Find the nearest parent of a specific type
         */
        public Value findParentOfType(Value node, String nodeType) {
            if (!traversed) {
                buildParentMap();
            }

            Value current = node;
            while (current != null) {
                current = parentMap.get(current);

                if (current != null &&
                        current.hasMember("type") &&
                        current.getMember("type").asString().equals(nodeType)) {

                    return current;
                }
            }

            return null;
        }

        /**
         * Checks if a node is a descendant of a node with the specified type
         */
        public boolean isDescendantOfType(Value node, String nodeType) {
            return findParentOfType(node, nodeType) != null;
        }

        /**
         * Check if a node is inside a conditional statement
         */
        public boolean isInConditional(Value node) {
            Value conditionalParent = findParentOfType(node, "IfStatement");
            if (conditionalParent != null) return true;

            conditionalParent = findParentOfType(node, "ConditionalExpression");
            if (conditionalParent != null) return true;

            conditionalParent = findParentOfType(node, "LogicalExpression");
            return conditionalParent != null;
        }

        /**
         * Check if a node is inside a loop
         */
        public boolean isInLoop(Value node) {
            Value loopParent = findParentOfType(node, "ForStatement");
            if (loopParent != null) return true;

            loopParent = findParentOfType(node, "ForInStatement");
            if (loopParent != null) return true;

            loopParent = findParentOfType(node, "ForOfStatement");
            if (loopParent != null) return true;

            loopParent = findParentOfType(node, "WhileStatement");
            if (loopParent != null) return true;

            loopParent = findParentOfType(node, "DoWhileStatement");
            return loopParent != null;
        }
    }
}