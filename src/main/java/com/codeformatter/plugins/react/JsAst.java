package com.codeformatter.plugins.react;

/**
 * Represents a JavaScript/React Abstract Syntax Tree.
 */
public class JsAst {
    private final String sourceCode;
    private boolean valid = true;
    private String error = null;

    public JsAst(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public JsAst(String sourceCode, boolean valid, String error) {
        this.sourceCode = sourceCode;
        this.valid = valid;
        this.error = error;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public boolean isValid() {
        return valid;
    }

    public String getError() {
        return error;
    }

    // In a real implementation, this would include methods to traverse and modify the AST
}
