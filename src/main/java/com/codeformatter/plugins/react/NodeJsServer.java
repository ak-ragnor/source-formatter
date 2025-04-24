package com.codeformatter.plugins.react;

import com.codeformatter.util.LoggerUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages a local Node.js server for JavaScript/React code processing. The server starts when
 * needed and shuts down when the formatter is closed.
 *
 * <p>This implementation includes improved resource handling to ensure reliable operation.
 */
public class NodeJsServer implements AutoCloseable {
  private static final Logger logger = LoggerUtil.getLogger(NodeJsServer.class);
  private static final int SERVER_PORT = 9567;
  private static final int SERVER_START_TIMEOUT_SEC = 30; // Increased timeout
  private static final String SERVER_URL = "http://localhost:" + SERVER_PORT;
  private static final ObjectMapper objectMapper = new ObjectMapper();

  // Add singleton pattern to prevent multiple server instances
  private static NodeJsServer instance;
  private static final Object instanceLock = new Object();

  private Process serverProcess;
  private boolean serverRunning = false;
  private boolean nodeJsAvailable = false;
  private String lastError = null;
  private Path serverScriptPath = null;

  // Required npm packages
  private static final String[] REQUIRED_PACKAGES = {
    "express", "prettier", "eslint", "eslint-plugin-react", "eslint-plugin-react-hooks"
  };

  /** Get a singleton instance of NodeJsServer. */
  public static NodeJsServer getInstance() {
    synchronized (instanceLock) {
      if (instance == null) {
        instance = new NodeJsServer();
      }
      return instance;
    }
  }

  /** Private constructor to enforce singleton pattern. */
  private NodeJsServer() {
    // Initialize in constructor
  }

  /**
   * Check if Node.js is available in the system. This performs a basic check to see if the 'node'
   * command exists.
   */
  public boolean isNodeJsAvailable() {
    if (nodeJsAvailable) {
      return true;
    }

    try {
      Process process = new ProcessBuilder("node", "--version").start();
      int exitCode = process.waitFor();
      if (exitCode == 0) {
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(process.getInputStream()))) {
          String version = reader.readLine();
          logger.info("Node.js detected: " + version);
          nodeJsAvailable = true;
          return true;
        }
      } else {
        logger.warning("Node.js check failed with exit code: " + exitCode);
        lastError = "Node.js not found in system PATH";
        return false;
      }
    } catch (IOException | InterruptedException e) {
      logger.warning("Node.js not available: " + e.getMessage());
      lastError = "Node.js not available: " + e.getMessage();
      return false;
    }
  }

  /** Start the Node.js server. */
  public boolean startServer() throws IOException {
    if (serverRunning && serverProcess != null && serverProcess.isAlive()) {
      return true;
    }

    // Check if Node.js is available first
    if (!isNodeJsAvailable()) {
      return false;
    }

    logger.info("Starting Node.js server...");

    // Find path to the server script
    serverScriptPath = findServerScript();
    if (serverScriptPath == null) {
      lastError = "Could not find server.js script";
      return false;
    }

    // Check and install required npm packages before starting the server
    ensureRequiredPackages();

    List<String> command = new ArrayList<>();
    command.add("node");
    command.add(serverScriptPath.toString());
    command.add(String.valueOf(SERVER_PORT));

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);

    try {
      serverProcess = pb.start();
    } catch (IOException e) {
      logger.log(Level.WARNING, "Failed to start Node.js server: " + e.getMessage(), e);
      lastError = "Failed to start Node.js server: " + e.getMessage();
      return false;
    }

    // Start a thread to consume and log the process output
    new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(new InputStreamReader(serverProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  logger.fine("NodeJsServer: " + line);
                  if (line.contains("Server listening on port")) {
                    serverRunning = true;
                  }
                }
              } catch (IOException e) {
                logger.log(Level.WARNING, "Error reading from Node.js server", e);
              }
            })
        .start();

    // Wait for server to start
    long startTime = System.currentTimeMillis();
    while (!serverRunning
        && System.currentTimeMillis() - startTime < SERVER_START_TIMEOUT_SEC * 1000) {
      try {
        TimeUnit.MILLISECONDS.sleep(100);

        // Try to ping the server
        if (isServerHealthy()) {
          serverRunning = true;
          break;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.log(Level.WARNING, "Interrupted while waiting for server to start", e);
        lastError = "Interrupted while waiting for server to start";
        stopServer();
        return false;
      }
    }

    if (!serverRunning) {
      stopServer();
      lastError = "Failed to start Node.js server within timeout";
      return false;
    }

    logger.info("Node.js server started successfully");
    return true;
  }

  /** Ensure all required npm packages are installed. */
  private void ensureRequiredPackages() {
    if (serverScriptPath == null) {
      logger.warning("Server script path not found, can't install packages");
      return;
    }

    Path nodeDir = serverScriptPath.getParent();
    if (nodeDir == null) {
      logger.warning("Cannot determine node directory from server script path");
      return;
    }

    // Check for package.json
    Path packageJsonPath = nodeDir.resolve("package.json");
    if (!Files.exists(packageJsonPath)) {
      // Create a minimal package.json if it doesn't exist
      try {
        String packageJsonContent =
            "{\n"
                + "  \"name\": \"advanced-formatter-js-tools\",\n"
                + "  \"version\": \"1.0.0\",\n"
                + "  \"private\": true,\n"
                + "  \"dependencies\": {\n"
                + "    \"express\": \"^4.18.2\",\n"
                + "    \"prettier\": \"^2.8.8\",\n"
                + "    \"eslint\": \"^8.46.0\",\n"
                + "    \"eslint-plugin-react\": \"^7.33.0\",\n"
                + "    \"eslint-plugin-react-hooks\": \"^4.6.0\"\n"
                + "  }\n"
                + "}";
        Files.writeString(packageJsonPath, packageJsonContent);
        logger.info("Created package.json at: " + packageJsonPath);
      } catch (IOException e) {
        logger.log(Level.WARNING, "Failed to create package.json", e);
      }
    }

    // Check for node_modules directory
    Path nodeModulesDir = nodeDir.resolve("node_modules");
    boolean needsInstall = !Files.exists(nodeModulesDir);

    // Check if specific required packages are missing
    if (!needsInstall) {
      for (String pkg : REQUIRED_PACKAGES) {
        Path packageDir = nodeModulesDir.resolve(pkg);
        if (!Files.exists(packageDir)) {
          needsInstall = true;
          logger.info("Required package missing: " + pkg);
          break;
        }
      }
    }

    if (needsInstall) {
      logger.info("Installing required npm packages in: " + nodeDir);
      try {
        ProcessBuilder pb = new ProcessBuilder("npm", "install");
        pb.directory(nodeDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Log npm install output
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(process.getInputStream()))) {
          String line;
          while ((line = reader.readLine()) != null) {
            logger.fine("npm install: " + line);
          }
        }

        int exitCode = process.waitFor();
        if (exitCode == 0) {
          logger.info("Successfully installed npm packages");
        } else {
          logger.warning("npm install failed with exit code: " + exitCode);
        }
      } catch (IOException | InterruptedException e) {
        logger.log(Level.WARNING, "Failed to install npm packages", e);
      }
    } else {
      logger.fine("All required npm packages are already installed");
    }
  }

  /** Find the location of the server.js script */
  private Path findServerScript() {
    logger.fine("Looking for server.js script...");

    // Try multiple locations to find the server script
    List<Path> possibleLocations = new ArrayList<>();

    // Current working directory and subdirectories
    possibleLocations.add(Paths.get("node", "server.js"));

    // User's home directory
    String userHome = System.getProperty("user.home");
    Path userHomeNode = Paths.get(userHome, ".codeformatter", "node", "server.js");
    possibleLocations.add(userHomeNode);

    // Installation directory (relative to current working directory)
    possibleLocations.add(Paths.get("node", "server.js"));

    // Application directory structure locations
    possibleLocations.add(
        Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "node", "server.js"));
    possibleLocations.add(
        Paths.get(
            System.getProperty("user.dir"), "build", "resources", "main", "node", "server.js"));
    possibleLocations.add(
        Paths.get(System.getProperty("user.dir"), "resources", "node", "server.js"));

    // Log attempted locations
    logger.fine("Searching for server.js in locations: " + possibleLocations);

    // Check each location
    for (Path path : possibleLocations) {
      if (Files.exists(path)) {
        logger.info("Found server script at: " + path);
        return path;
      }
    }

    // Extract from classpath if not found in the filesystem
    try {
      // Check if resource exists in classpath
      URL serverJsResource = getClass().getClassLoader().getResource("node/server.js");
      if (serverJsResource != null) {
        logger.info("Found server.js in classpath at: " + serverJsResource);

        // Create temp directory for extraction
        Path tempDir = Files.createTempDirectory("codeformatter-node");
        tempDir.toFile().deleteOnExit();

        Path nodeDir = tempDir.resolve("node");
        Files.createDirectories(nodeDir);

        // Extract to temp directory
        Path tempServerJs = nodeDir.resolve("server.js");
        try (InputStream in = serverJsResource.openStream();
            OutputStream out = new FileOutputStream(tempServerJs.toFile())) {
          byte[] buffer = new byte[8192];
          int bytesRead;
          while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
          }
        }

        logger.info("Extracted server.js to temporary location: " + tempServerJs);
        return tempServerJs;
      }

      // If not found in resources, try to extract to the user's home directory
      if (!Files.exists(userHomeNode)) {
        // Create node directory in user home if it doesn't exist
        Files.createDirectories(userHomeNode.getParent());

        // Try to find the resource
        InputStream serverJsStream =
            getClass().getClassLoader().getResource("node/server.js").openStream();
        if (serverJsStream != null) {
          // Copy to user home
          try (OutputStream out = new FileOutputStream(userHomeNode.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = serverJsStream.read(buffer)) != -1) {
              out.write(buffer, 0, bytesRead);
            }
          }
          logger.info("Extracted server.js to user home: " + userHomeNode);
          return userHomeNode;
        }
      }

    } catch (Exception e) {
      logger.log(Level.WARNING, "Error extracting server.js from resources", e);
    }

    logger.warning("Could not find server.js script in any location");
    return null;
  }

  /** Check if the server is healthy by pinging the /health endpoint */
  private boolean isServerHealthy() {
    try {
      URL url = new URI(SERVER_URL + "/health").toURL();
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(1000);
      conn.setReadTimeout(1000);

      int responseCode = conn.getResponseCode();
      return responseCode == 200;
    } catch (Exception e) {
      return false;
    }
  }

  /** Format JavaScript/React code using the server. */
  public String formatCode(String sourceCode, boolean isReact) throws IOException {
    if (!serverRunning) {
      boolean started = startServer();
      if (!started) {
        throw new IOException("Node.js server not available: " + lastError);
      }
    }

    try {
      URL url = new URI(SERVER_URL + "/format").toURL();
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Accept", "application/json");
      conn.setDoOutput(true);

      // Create request JSON
      ObjectNode requestNode = objectMapper.createObjectNode();
      requestNode.put("code", sourceCode);
      requestNode.put("isReact", isReact);

      String requestJson = objectMapper.writeValueAsString(requestNode);

      // Send request
      try (OutputStream os = conn.getOutputStream()) {
        byte[] input = requestJson.getBytes(StandardCharsets.UTF_8);
        os.write(input, 0, input.length);
      }

      // Read response
      StringBuilder response = new StringBuilder();
      try (BufferedReader br =
          new BufferedReader(
              new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) {
          response.append(line);
        }
      }

      // Parse JSON response
      JsonNode responseNode = objectMapper.readTree(response.toString());

      if (responseNode.has("success") && responseNode.get("success").asBoolean()) {
        return responseNode.get("formattedCode").asText();
      } else if (responseNode.has("error")) {
        throw new IOException("Error formatting code: " + responseNode.get("error").asText());
      } else {
        throw new IOException("Unknown error in formatting response");
      }
    } catch (URISyntaxException | JsonProcessingException e) {
      throw new IOException("Error communicating with Node.js server: " + e.getMessage(), e);
    }
  }

  /** Analyze JavaScript/React code using the server. */
  public List<LintIssue> analyzeCode(String sourceCode, boolean isReact) throws IOException {
    if (!serverRunning) {
      boolean started = startServer();
      if (!started) {
        throw new IOException("Node.js server not available: " + lastError);
      }
    }

    // Find or create eslintrc.js file in the node directory
    Path eslintConfigFile = _ensureEslintConfig();
    if (eslintConfigFile == null) {
      logger.warning("Could not find or create ESLint config file, analysis will be limited");
    }

    try {
      URL url = new URI(SERVER_URL + "/analyze").toURL();
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Accept", "application/json");
      conn.setDoOutput(true);

      // Create request JSON
      ObjectNode requestNode = objectMapper.createObjectNode();
      requestNode.put("code", sourceCode);
      requestNode.put("isReact", isReact);
      // Add the eslint config path if available
      if (eslintConfigFile != null) {
        requestNode.put("eslintConfigPath", eslintConfigFile.toString());
      }

      String requestJson = objectMapper.writeValueAsString(requestNode);

      // Send request
      try (OutputStream os = conn.getOutputStream()) {
        byte[] input = requestJson.getBytes(StandardCharsets.UTF_8);
        os.write(input, 0, input.length);
      }

      // Check response code
      int responseCode = conn.getResponseCode();
      if (responseCode != 200) {
        try (BufferedReader br =
                     new BufferedReader(
                             new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
          StringBuilder errorResponse = new StringBuilder();
          String line;
          while ((line = br.readLine()) != null) {
            errorResponse.append(line);
          }
          throw new IOException(
                  "Server returned error code " + responseCode + ": " + errorResponse.toString());
        }
      }

      // Read successful response
      StringBuilder response = new StringBuilder();
      try (BufferedReader br =
                   new BufferedReader(
                           new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) {
          response.append(line);
        }
      }

      // Parse JSON response
      JsonNode responseNode = objectMapper.readTree(response.toString());

      List<LintIssue> issues = new ArrayList<>();

      if (responseNode.has("success")
              && responseNode.get("success").asBoolean()
              && responseNode.has("issues")) {

        JsonNode issuesNode = responseNode.get("issues");
        if (issuesNode.isArray()) {
          for (JsonNode issueNode : issuesNode) {
            issues.add(
                    new LintIssue(
                            issueNode.has("ruleId") ? issueNode.get("ruleId").asText() : "",
                            issueNode.has("severity") ? issueNode.get("severity").asText() : "info",
                            issueNode.has("message") ? issueNode.get("message").asText() : "",
                            issueNode.has("line") ? issueNode.get("line").asInt() : 1,
                            issueNode.has("column") ? issueNode.get("column").asInt() : 1));
          }
        }

        return issues;
      } else if (responseNode.has("error")) {
        throw new IOException("Error analyzing code: " + responseNode.get("error").asText());
      } else {
        throw new IOException("Unknown error in analysis response");
      }
    } catch (URISyntaxException | JsonProcessingException e) {
      throw new IOException("Error communicating with Node.js server: " + e.getMessage(), e);
    }
  }

  /**
   * Ensure ESLint configuration file exists in the node directory.
   * @return Path to the ESLint config file
   */
  private Path _ensureEslintConfig() {
    if (serverScriptPath == null) {
      logger.warning("Server script path not found, can't locate ESLint config");
      return null;
    }

    Path nodeDir = serverScriptPath.getParent();
    if (nodeDir == null) {
      logger.warning("Cannot determine node directory from server script path");
      return null;
    }

    // Check for .eslintrc.js in the node directory
    Path eslintConfigPath = nodeDir.resolve(".eslintrc.js");

    if (!Files.exists(eslintConfigPath)) {
      // Check in parent directory
      Path parentEslintConfig = nodeDir.getParent().resolve(".eslintrc.js");

      if (Files.exists(parentEslintConfig)) {
        // Copy from parent to node directory
        try {
          Files.copy(parentEslintConfig, eslintConfigPath);
          logger.info("Copied .eslintrc.js from parent directory to node directory");
        } catch (IOException e) {
          logger.log(Level.WARNING, "Failed to copy .eslintrc.js: " + e.getMessage(), e);

          // Create a basic .eslintrc.js if copying failed
          try {
            String eslintConfig = "module.exports = {\n" +
                    "  env: {\n" +
                    "    browser: true,\n" +
                    "    es2021: true,\n" +
                    "    node: true,\n" +
                    "  },\n" +
                    "  extends: [\n" +
                    "    'eslint:recommended'\n" +
                    "  ],\n" +
                    "  parserOptions: {\n" +
                    "    ecmaFeatures: {\n" +
                    "      jsx: true,\n" +
                    "    },\n" +
                    "    ecmaVersion: 'latest',\n" +
                    "    sourceType: 'module',\n" +
                    "  },\n" +
                    "  plugins: [],\n" +
                    "  rules: {}\n" +
                    "};\n";
            Files.writeString(eslintConfigPath, eslintConfig);
            logger.info("Created basic .eslintrc.js in node directory");
          } catch (IOException ex) {
            logger.log(Level.WARNING, "Failed to create .eslintrc.js: " + ex.getMessage(), ex);
            return null;
          }
        }
      } else {
        // No existing config, create a basic one
        try {
          String eslintConfig = "module.exports = {\n" +
                  "  env: {\n" +
                  "    browser: true,\n" +
                  "    es2021: true,\n" +
                  "    node: true,\n" +
                  "  },\n" +
                  "  extends: [\n" +
                  "    'eslint:recommended'\n" +
                  "  ],\n" +
                  "  parserOptions: {\n" +
                  "    ecmaFeatures: {\n" +
                  "      jsx: true,\n" +
                  "    },\n" +
                  "    ecmaVersion: 'latest',\n" +
                  "    sourceType: 'module',\n" +
                  "  },\n" +
                  "  plugins: [],\n" +
                  "  rules: {}\n" +
                  "};\n";
          Files.writeString(eslintConfigPath, eslintConfig);
          logger.info("Created basic .eslintrc.js in node directory");
        } catch (IOException e) {
          logger.log(Level.WARNING, "Failed to create .eslintrc.js: " + e.getMessage(), e);
          return null;
        }
      }
    }

    return eslintConfigPath;
  }

  /** Configure the formatter with specific options. */
  public boolean configure(Map<String, Object> options) {
    if (!serverRunning) {
      try {
        boolean started = startServer();
        if (!started) {
          logger.warning("Cannot configure Node.js server - not running: " + lastError);
          return false;
        }
      } catch (IOException e) {
        logger.log(Level.WARNING, "Failed to start server for configuration: " + e.getMessage(), e);
        return false;
      }
    }

    try {
      URL url = new URI(SERVER_URL + "/configure").toURL();
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Accept", "application/json");
      conn.setDoOutput(true);

      // Convert options to JSON
      String requestJson = objectMapper.writeValueAsString(options);

      // Send request
      try (OutputStream os = conn.getOutputStream()) {
        byte[] input = requestJson.getBytes(StandardCharsets.UTF_8);
        os.write(input, 0, input.length);
      }

      // Check response code
      int responseCode = conn.getResponseCode();
      if (responseCode != 200) {
        logger.warning("Server returned error code " + responseCode + " during configuration");
        return false;
      }

      logger.fine("Node.js server configured successfully");
      return true;
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error configuring Node.js server: " + e.getMessage(), e);
      return false;
    }
  }

  /** Stop the Node.js server. */
  public void stopServer() {
    if (serverProcess != null) {
      logger.info("Stopping Node.js server...");
      serverProcess.destroy();
      try {
        if (!serverProcess.waitFor(5, TimeUnit.SECONDS)) {
          serverProcess.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.warning("Interrupted while stopping Node.js server");
        serverProcess.destroyForcibly();
      }
      serverProcess = null;
      serverRunning = false;
      logger.info("Node.js server stopped");
    }
  }

  /** Check if the server is running. */
  public boolean isRunning() {
    return serverRunning && serverProcess != null && serverProcess.isAlive();
  }

  /** Get the last error message if any. */
  public String getLastError() {
    return lastError;
  }

  /** Close the server when the formatter is closed. */
  @Override
  public void close() {
    stopServer();
  }

  /** Represents an ESLint issue. */
  public record LintIssue(String ruleId, String severity, String message, int line, int column) {}
}
