package com.codeformatter.plugins.react;

import com.codeformatter.util.LoggerUtil;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JavaScript engine bridge implementation using GraalJS.
 * This provides integration with JavaScript-based parsers and transformers.
 */
public class JsEngine implements AutoCloseable {
    private static final Logger logger = LoggerUtil.getLogger(JsEngine.class.getName());

    private final Context context;
    private boolean initialized = false;
    private final Lock contextLock = new ReentrantLock();
    private final AtomicInteger operationCount = new AtomicInteger(0);
    private boolean closed = false;

    // Resources
    private static final String BABEL_PARSER_RESOURCE = "/js/babel-standalone.min.js";
    private static final String PRETTIER_RESOURCE = "/js/prettier.min.js";
    private static final String PARSER_SCRIPT_RESOURCE = "/js/react-parser.js";

    // Resource limits
    private static final int MEMORY_LIMIT_MB = 512;
    private static final int EXECUTION_TIMEOUT_SEC = 30;
    private static final int LOCK_TIMEOUT_SEC = 10;

    /**
     * Creates a new JavaScript engine with resource limits.
     */
    public JsEngine() {
        context = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowExperimentalOptions(true)
                .option("js.memory-limit", String.valueOf(MEMORY_LIMIT_MB * 1024 * 1024))
                .option("js.execution-timeout", String.valueOf(EXECUTION_TIMEOUT_SEC * 1000))
                .build();

        try {
            initializeEngine();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to initialize JavaScript engine", e);
            throw new RuntimeException("Failed to initialize JavaScript engine: " + e.getMessage(), e);
        }
    }

    /**
     * Initializes the JavaScript engine by loading required libraries.
     */
    private void initializeEngine() throws IOException {
        if (initialized) {
            return;
        }

        try {
            _loadResource(BABEL_PARSER_RESOURCE);
            _loadResource(PRETTIER_RESOURCE);
            _loadResource(PARSER_SCRIPT_RESOURCE);

            initialized = true;
            logger.info("JavaScript engine initialized successfully");
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to load JavaScript resources", e);
            throw e;
        }
    }

    /**
     * Loads a JavaScript resource into the engine.
     */
    private void _loadResource(String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                logger.severe("Resource not found: " + resourcePath);
                throw new IOException("Resource not found: " + resourcePath);
            }

            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                try {
                    context.eval(Source.newBuilder("js", reader, resourcePath).build());
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Error evaluating JavaScript: " + resourcePath, e);
                    throw new IOException("Error loading JavaScript resource: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Parse React/JavaScript code into an AST representation.
     * Thread-safe and handles resource cleanup.
     */
    public JsAst parseReactCode(String sourceCode, boolean isTypeScript) {
        if (closed) {
            return new JsAst(sourceCode, false, "JavaScript engine has been closed");
        }

        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            return new JsAst(sourceCode, false, "Empty source code provided");
        }

        if (!initialized) {
            return new JsAst(sourceCode, false, "JavaScript engine is not initialized");
        }

        if (!acquireLock()) {
            return new JsAst(sourceCode, false, "Timed out waiting for JavaScript engine access");
        }

        operationCount.incrementAndGet();
        try {
            context.getBindings("js").putMember("sourceCode", sourceCode);
            context.getBindings("js").putMember("isTypeScript", isTypeScript);

            Value result;
            try {
                result = context.eval("js", "parseReactCode(sourceCode, isTypeScript)");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error parsing React code", e);
                if (e.getMessage().contains("timeout")) {
                    return new JsAst(sourceCode, false, "JavaScript parsing timed out. The code may be too complex or large.");
                }
                return new JsAst(sourceCode, false, "Error parsing code: " + e.getMessage());
            }

            if (result.hasMember("error")) {
                String error = result.getMember("error").asString();
                return new JsAst(sourceCode, false, error);
            }

            context.getBindings("js").putMember("currentAst", result.getMember("ast"));
            return new JsAst(sourceCode, true, null, result.getMember("ast"));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error in JavaScript parsing", e);
            return new JsAst(sourceCode, false,
                    "Error parsing code: " + e.getMessage() +
                            (e.getCause() != null ? " Cause: " + e.getCause().getMessage() : ""));
        } finally {
            contextLock.unlock();
        }
    }

    /**
     * Generate formatted code from the AST.
     * Thread-safe and handles resource cleanup.
     */
    public String generateCode(JsAst ast) {
        if (closed) {
            logger.warning("JavaScript engine has been closed. Returning original code.");
            return ast.getSourceCode();
        }

        if (!ast.isValid()) {
            return ast.getSourceCode();
        }

        if (!initialized) {
            logger.warning("JavaScript engine is not initialized. Returning original code.");
            return ast.getSourceCode();
        }

        if (!acquireLock()) {
            logger.warning("Timed out waiting for JavaScript engine access. Returning original code.");
            return ast.getSourceCode();
        }

        operationCount.incrementAndGet();
        try {
            Value result = context.eval("js", "generateCodeFromAst(currentAst)");

            if (result.hasMember("error")) {
                logger.warning("Error generating code: " + result.getMember("error").asString());
                return ast.getSourceCode();
            }

            return result.getMember("code").asString();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error in code generation", e);
            return ast.getSourceCode();
        } finally {
            contextLock.unlock();
        }
    }

    /**
     * Apply a transformation to the AST.
     * Thread-safe and handles resource cleanup.
     */
    public boolean transformAst(JsAst ast, String transformationName, Map<String, Object> options) {
        if (closed) {
            logger.warning("JavaScript engine has been closed. Cannot transform AST.");
            return false;
        }

        if (!ast.isValid()) {
            return false;
        }

        if (!initialized) {
            logger.warning("JavaScript engine is not initialized. Cannot transform AST.");
            return false;
        }

        if (!acquireLock()) {
            logger.warning("Timed out waiting for JavaScript engine access. Cannot transform AST.");
            return false;
        }

        operationCount.incrementAndGet();
        try {
            context.getBindings("js").putMember("transformOptions", options);
            context.getBindings("js").putMember("transformName", transformationName);

            Value result = context.eval("js", "applyTransformation(currentAst, transformName, transformOptions)");
            return result.getMember("success").asBoolean();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error transforming AST: " + transformationName, e);
            return false;
        } finally {
            contextLock.unlock();
        }
    }

    /**
     * Try to acquire the context lock with a timeout.
     * @return true if lock was acquired, false otherwise
     */
    private boolean acquireLock() {
        try {
            return contextLock.tryLock(LOCK_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Gets the total number of operations performed by this engine.
     */
    public int getOperationCount() {
        return operationCount.get();
    }

    /**
     * Checks if the engine has been closed.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Closes the JavaScript engine and frees resources.
     * This method should be called when the engine is no longer needed.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }

        if (acquireLock()) {
            try {
                closed = true;
                if (context != null) {
                    try {
                        context.close(true);
                        logger.info("JavaScript engine closed successfully after " +
                                operationCount.get() + " operations");
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error closing JavaScript engine", e);
                    }
                }
            } finally {
                contextLock.unlock();
            }
        } else {
            logger.warning("Could not acquire lock to close JavaScript engine");
        }
    }
}