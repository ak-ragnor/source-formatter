package com.codeformatter.plugins.react;

import org.graalvm.polyglot.Value;

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
}