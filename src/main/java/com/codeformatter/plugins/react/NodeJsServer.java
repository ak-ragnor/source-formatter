package com.codeformatter.plugins.react;

import com.codeformatter.util.LoggerUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
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
 */
public class NodeJsServer implements AutoCloseable {
  private static final Logger logger = LoggerUtil.getLogger(NodeJsServer.class);
  private static final int SERVER_PORT = 9567;
  private static final int SERVER_START_TIMEOUT_SEC = 10;
  private static final String SERVER_URL = "http://localhost:" + SERVER_PORT;
  private static final ObjectMapper objectMapper = new ObjectMapper();

  private Process serverProcess;
  private boolean serverRunning = false;

  /** Start the Node.js server. */
  public void startServer() throws IOException {
    if (serverRunning && serverProcess != null && serverProcess.isAlive()) {
      return;
    }

    logger.info("Starting Node.js server...");

    // Find path to the server script
    Path serverScript = findServerScript();

    List<String> command = new ArrayList<>();
    command.add("node");
    command.add(serverScript.toString());
    command.add(String.valueOf(SERVER_PORT));

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);

    serverProcess = pb.start();

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
        throw new IOException("Interrupted while waiting for server to start");
      }
    }

    if (!serverRunning) {
      stopServer();
      throw new IOException("Failed to start Node.js server within timeout");
    }

    logger.info("Node.js server started successfully");
  }

  /** Find the location of the server.js script */
  private Path findServerScript() throws IOException {
    // Try multiple locations to find the server script
    List<Path> possibleLocations = new ArrayList<>();

    // Current working directory
    possibleLocations.add(Paths.get("node", "server.js"));

    // Resources directory
    possibleLocations.add(
        Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "node", "server.js"));

    // Build directory
    possibleLocations.add(
        Paths.get(
            System.getProperty("user.dir"), "build", "resources", "main", "node", "server.js"));

    // Class path resource
    URL resource = NodeJsServer.class.getClassLoader().getResource("node/server.js");
    if (resource != null) {
      try {
        possibleLocations.add(Paths.get(resource.toURI()));
      } catch (URISyntaxException e) {
        logger.log(Level.WARNING, "Invalid URI for server script", e);
      }
    }

    // Check each location
    for (Path path : possibleLocations) {
      if (Files.exists(path)) {
        logger.info("Found server script at: " + path);
        return path;
      }
    }

    // If we reach here, we couldn't find the script
    throw new IOException("Could not find server.js script. Searched in: " + possibleLocations);
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
      startServer();
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
      startServer();
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

  /** Configure the formatter with specific options. */
  public void configure(Map<String, Object> options) {
    if (!serverRunning) {
      try {
        startServer();
      } catch (IOException e) {
        logger.log(Level.WARNING, "Failed to start server for configuration: " + e.getMessage(), e);
        return;
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
        return;
      }

      logger.fine("Node.js server configured successfully");
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error configuring Node.js server: " + e.getMessage(), e);
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

  /** Close the server when the formatter is closed. */
  @Override
  public void close() {
    stopServer();
  }

  /** Represents an ESLint issue. */
  public static class LintIssue {
    private final String ruleId;
    private final String severity;
    private final String message;
    private final int line;
    private final int column;

    public LintIssue(String ruleId, String severity, String message, int line, int column) {
      this.ruleId = ruleId;
      this.severity = severity;
      this.message = message;
      this.line = line;
      this.column = column;
    }

    // Getters
    public String getRuleId() {
      return ruleId;
    }

    public String getSeverity() {
      return severity;
    }

    public String getMessage() {
      return message;
    }

    public int getLine() {
      return line;
    }

    public int getColumn() {
      return column;
    }
  }
}
