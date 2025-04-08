package com.codeformatter.plugins.react;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * JavaScript engine bridge implementation using GraalJS.
 * This provides integration with JavaScript-based parsers and transformers.
 */
public class JsEngine {
    private final Context context;
    private boolean initialized = false;
    private static final String BABEL_PARSER_RESOURCE = "/js/babel-standalone.min.js";
    private static final String PRETTIER_RESOURCE = "/js/prettier.min.js";
    private static final String PARSER_SCRIPT_RESOURCE = "/js/react-parser.js";

    public JsEngine() {
        // Create a GraalVM context with all permissions to allow file operations
        context = Context.newBuilder("js")
                .allowAllAccess(true)
                .build();

        try {
            initializeEngine();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize JavaScript engine: " + e.getMessage(), e);
        }
    }

    private void initializeEngine() throws IOException {
        if (initialized) {
            return;
        }

        // Load Babel parser
        loadResource(BABEL_PARSER_RESOURCE);

        // Load Prettier
        loadResource(PRETTIER_RESOURCE);

        // Load our custom parser wrapper
        loadResource(PARSER_SCRIPT_RESOURCE);

        initialized = true;
    }

    private void loadResource(String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }

            InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
            context.eval(Source.newBuilder("js", reader, resourcePath).build());
        }
    }

    /**
     * Parse React/JavaScript code into an AST representation
     */
    public JsAst parseReactCode(String sourceCode, boolean isTypeScript) {
        try {
            // Add the source code to the context
            context.getBindings("js").putMember("sourceCode", sourceCode);
            context.getBindings("js").putMember("isTypeScript", isTypeScript);

            // Call the parse function
            Value result = context.eval("js", "parseReactCode(sourceCode, isTypeScript)");

            if (result.hasMember("error")) {
                String error = result.getMember("error").asString();
                return new JsAst(sourceCode, false, error);
            }

            // Store the AST in the JS context to be accessed later
            context.getBindings("js").putMember("currentAst", result.getMember("ast"));

            return new JsAst(sourceCode, true, null, result.getMember("ast"));
        } catch (Exception e) {
            return new JsAst(sourceCode, false, "Error parsing code: " + e.getMessage());
        }
    }

    /**
     * Generate formatted code from the AST
     */
    public String generateCode(JsAst ast) {
        if (!ast.isValid()) {
            return ast.getSourceCode(); // Return original code if AST is invalid
        }

        try {
            // The AST is already in the JS context, now generate code from it
            Value result = context.eval("js", "generateCodeFromAst(currentAst)");

            if (result.hasMember("error")) {
                // If there was an error, return the original code
                return ast.getSourceCode();
            }

            return result.getMember("code").asString();
        } catch (Exception e) {
            // In case of any error, return the original code
            return ast.getSourceCode();
        }
    }

    /**
     * Apply a transformation to the AST
     */
    public boolean transformAst(JsAst ast, String transformationName, Map<String, Object> options) {
        if (!ast.isValid()) {
            return false;
        }

        try {
            // Convert Java map to JS object
            context.getBindings("js").putMember("transformOptions", options);
            context.getBindings("js").putMember("transformName", transformationName);

            // Apply the transformation
            Value result = context.eval("js", "applyTransformation(currentAst, transformName, transformOptions)");

            return result.getMember("success").asBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Close the JavaScript engine and free resources
     */
    public void close() {
        if (context != null) {
            context.close();
        }
    }
}