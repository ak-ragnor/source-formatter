package com.codeformatter.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.*;

/**
 * Utility class for configuring and managing logging throughout the application.
 */
public class LoggerUtil {
    private static final Logger rootLogger = Logger.getLogger("");
    private static final String DEFAULT_LOG_CONFIG = "/logging.properties";
    private static boolean initialized = false;
    private static Level consoleLevel = Level.INFO;
    private static Path logFilePath = Paths.get("formatter.log");

    /**
     * Initializes the logging system with default configuration.
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        try {
            try (InputStream is = LoggerUtil.class.getResourceAsStream(DEFAULT_LOG_CONFIG)) {
                if (is != null) {
                    LogManager.getLogManager().readConfiguration(is);
                    initialized = true;
                    return;
                }
            }

            configureBasicLogging();
            initialized = true;
        } catch (Exception e) {
            System.err.println("Failed to initialize logging: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sets up basic logging with console and file handlers.
     */
    private static void configureBasicLogging() throws IOException {
        // Reset existing handlers
        for (Handler handler : rootLogger.getHandlers()) {
            rootLogger.removeHandler(handler);
        }

        // Console handler
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(consoleLevel);
        consoleHandler.setFormatter(new SimpleFormatter());

        // File handler
        FileHandler fileHandler = new FileHandler(logFilePath.toString(), true);
        fileHandler.setLevel(Level.ALL);
        fileHandler.setFormatter(new SimpleFormatter());

        // Add handlers to root logger
        rootLogger.addHandler(consoleHandler);
        rootLogger.addHandler(fileHandler);
        rootLogger.setLevel(Level.ALL);
    }

    /**
     * Sets the console logging level.
     */
    public static void setConsoleLevel(Level level) {
        consoleLevel = level;

        if (initialized) {
            for (Handler handler : rootLogger.getHandlers()) {
                if (handler instanceof ConsoleHandler) {
                    handler.setLevel(level);
                }
            }
        }
    }

    /**
     * Sets the log file path.
     */
    public static void setLogFilePath(Path path) {
        logFilePath = path;

        // If already initialized, replace the file handler
        if (initialized) {
            try {
                // Remove existing file handlers
                for (Handler handler : rootLogger.getHandlers()) {
                    if (handler instanceof FileHandler) {
                        rootLogger.removeHandler(handler);
                        handler.close();
                    }
                }

                // Add new file handler
                FileHandler fileHandler = new FileHandler(logFilePath.toString(), true);
                fileHandler.setLevel(Level.ALL);
                fileHandler.setFormatter(new SimpleFormatter());
                rootLogger.addHandler(fileHandler);
            } catch (IOException e) {
                Logger.getLogger(LoggerUtil.class.getName()).log(
                        Level.SEVERE, "Failed to update log file path", e);
            }
        }
    }

    /**
     * Gets a logger for a specific class.
     */
    public static Logger getLogger(Class<?> clazz) {
        if (!initialized) {
            initialize();
        }
        return Logger.getLogger(clazz.getName());
    }

    /**
     * Gets a logger for a specific name.
     */
    public static Logger getLogger(String name) {
        if (!initialized) {
            initialize();
        }
        return Logger.getLogger(name);
    }

    /**
     * Shuts down the logging system.
     */
    public static void shutdown() {
        for (Handler handler : rootLogger.getHandlers()) {
            handler.close();
        }
    }
}