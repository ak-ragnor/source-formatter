package com.codeformatter.config;

import com.codeformatter.util.LoggerUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Enhanced configuration loader with better error handling, validation, and defaults.
 */
public class ConfigurationLoader {
    private static final Logger logger = LoggerUtil.getLogger(ConfigurationLoader.class.getName());
    private static final String DEFAULT_CONFIG_RESOURCE = "/config/default-config.yml";

    private static FormatterConfig _cachedDefaultConfig = null;

    /**
     * Loads configuration from a file with fallback to defaults.
     * Improved error handling and validation.
     */
    public static FormatterConfig loadConfig(Path configPath) {
        if (configPath == null) {
            logger.warning("No config path provided, using default configuration");
            return loadDefaultConfig();
        }

        if (!Files.exists(configPath)) {
            logger.warning("Configuration file not found: " + configPath + ", using default configuration");
            return loadDefaultConfig();
        }

        try {
            logger.info("Loading configuration from: " + configPath);

            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            @SuppressWarnings("unchecked")
            Map<String, Object> config = mapper.readValue(configPath.toFile(), Map.class);

            FormatterConfig formatterConfig = _createConfigFromMap(config);
            logger.info("Configuration loaded successfully with " +
                    formatterConfig.getPluginConfigsMap().size() + " plugin configurations");

            return formatterConfig;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error parsing configuration file: " + e.getMessage(), e);
            logger.info("Falling back to default configuration");
            return loadDefaultConfig();
        }
    }

    /**
     * Loads the embedded default configuration with caching.
     */
    public static FormatterConfig loadDefaultConfig() {
        if (_cachedDefaultConfig != null) {
            return _cachedDefaultConfig;
        }

        try (InputStream defaultConfigStream =
                     ConfigurationLoader.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {

            if (defaultConfigStream == null) {
                logger.severe("Default configuration resource not found: " + DEFAULT_CONFIG_RESOURCE);
                return _createEmptyConfig();
            }

            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            @SuppressWarnings("unchecked")
            Map<String, Object> config = mapper.readValue(defaultConfigStream, Map.class);

            _cachedDefaultConfig = _createConfigFromMap(config);
            logger.info("Default configuration loaded successfully");

            return _cachedDefaultConfig;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to load default configuration", e);
            return _createEmptyConfig();
        }
    }

    /**
     * Creates a configuration from a parsed Map, with validation.
     */
    @SuppressWarnings("unchecked")
    private static FormatterConfig _createConfigFromMap(Map<String, Object> config) {
        Map<String, Object> generalConfig = new HashMap<>();
        if (config.containsKey("general") && config.get("general") instanceof Map) {
            generalConfig = new HashMap<>((Map<String, Object>) config.get("general"));
        } else {
            logger.warning("Missing or invalid 'general' section in config, using defaults");
        }

        _ensureDefaultGeneralConfig(generalConfig);

        Map<String, Map<String, Object>> pluginConfigs = new HashMap<>();
        if (config.containsKey("plugins") && config.get("plugins") instanceof Map) {
            Map<String, Object> pluginsMap = (Map<String, Object>) config.get("plugins");

            for (Map.Entry<String, Object> entry : pluginsMap.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    pluginConfigs.put(entry.getKey(), new HashMap<>((Map<String, Object>) entry.getValue()));
                } else {
                    logger.warning("Invalid configuration for plugin '" + entry.getKey() + "', using defaults");
                    pluginConfigs.put(entry.getKey(), new HashMap<>());
                }
            }
        } else {
            logger.warning("Missing or invalid 'plugins' section in config, using defaults");
        }

        _ensureDefaultPluginConfigs(pluginConfigs);

        _validateConfigurationValues(generalConfig, pluginConfigs);

        return new FormatterConfig(generalConfig, pluginConfigs);
    }

    /**
     * Validates configuration values to ensure they are within acceptable ranges.
     */
    private static void _validateConfigurationValues(Map<String, Object> generalConfig,
                                                     Map<String, Map<String, Object>> pluginConfigs) {
        _validateIntRange(generalConfig, "indentSize", 1, 8);
        _validateIntRange(generalConfig, "tabWidth", 1, 8);
        _validateIntRange(generalConfig, "lineLength", 40, 200);

        if (pluginConfigs.containsKey("spring")) {
            Map<String, Object> springConfig = pluginConfigs.get("spring");
            _validateIntRange(springConfig, "maxMethodLines", 10, 500);
            _validateIntRange(springConfig, "maxMethodComplexity", 1, 100);
        }

        if (pluginConfigs.containsKey("react")) {
            Map<String, Object> reactConfig = pluginConfigs.get("react");
            _validateIntRange(reactConfig, "maxComponentLines", 10, 1000);
        }
    }

    /**
     * Validates that an integer configuration value is within the specified range.
     */
    private static void _validateIntRange(Map<String, Object> config, String key, int min, int max) {
        if (config.containsKey(key) && config.get(key) instanceof Number) {
            int value = ((Number) config.get(key)).intValue();
            if (value < min || value > max) {
                logger.warning("Configuration value '" + key + "' is outside acceptable range " +
                        "(" + min + "-" + max + "). Using default value.");
                config.remove(key);
            }
        }
    }

    /**
     * Creates an empty configuration with minimum defaults.
     */
    private static FormatterConfig _createEmptyConfig() {
        Map<String, Object> generalConfig = new HashMap<>();
        _ensureDefaultGeneralConfig(generalConfig);

        Map<String, Map<String, Object>> pluginConfigs = new HashMap<>();
        _ensureDefaultPluginConfigs(pluginConfigs);

        return new FormatterConfig(generalConfig, pluginConfigs);
    }

    /**
     * Ensures that general configuration has all required default values.
     */
    private static void _ensureDefaultGeneralConfig(Map<String, Object> generalConfig) {
        if (!generalConfig.containsKey("indentSize") || !(generalConfig.get("indentSize") instanceof Number)) {
            generalConfig.put("indentSize", 4);
        }
        if (!generalConfig.containsKey("tabWidth") || !(generalConfig.get("tabWidth") instanceof Number)) {
            generalConfig.put("tabWidth", 4);
        }
        if (!generalConfig.containsKey("useTabs") || !(generalConfig.get("useTabs") instanceof Boolean)) {
            generalConfig.put("useTabs", false);
        }
        if (!generalConfig.containsKey("lineLength") || !(generalConfig.get("lineLength") instanceof Number)) {
            generalConfig.put("lineLength", 100);
        }
        if (!generalConfig.containsKey("ignoreFiles")) {
            generalConfig.put("ignoreFiles", new ArrayList<String>());
        } else if (!(generalConfig.get("ignoreFiles") instanceof List)) {
            generalConfig.put("ignoreFiles", new ArrayList<String>());
        }
    }

    /**
     * Ensures that plugin configurations have all required default values.
     */
    private static void _ensureDefaultPluginConfigs(Map<String, Map<String, Object>> pluginConfigs) {
        Map<String, Object> springConfig = pluginConfigs.computeIfAbsent("spring", k -> new HashMap<>());
        if (!springConfig.containsKey("maxMethodLines") || !(springConfig.get("maxMethodLines") instanceof Number)) {
            springConfig.put("maxMethodLines", 50);
        }
        if (!springConfig.containsKey("maxMethodComplexity") || !(springConfig.get("maxMethodComplexity") instanceof Number)) {
            springConfig.put("maxMethodComplexity", 15);
        }
        if (!springConfig.containsKey("enforceDesignPatterns") || !(springConfig.get("enforceDesignPatterns") instanceof Boolean)) {
            springConfig.put("enforceDesignPatterns", true);
        }
        if (!springConfig.containsKey("enforceDependencyInjection") || !(springConfig.get("enforceDependencyInjection") instanceof String)) {
            springConfig.put("enforceDependencyInjection", "constructor");
        }

        Map<String, Object> reactConfig = pluginConfigs.computeIfAbsent("react", k -> new HashMap<>());
        if (!reactConfig.containsKey("maxComponentLines") || !(reactConfig.get("maxComponentLines") instanceof Number)) {
            reactConfig.put("maxComponentLines", 150);
        }
        if (!reactConfig.containsKey("enforceHookDependencies") || !(reactConfig.get("enforceHookDependencies") instanceof Boolean)) {
            reactConfig.put("enforceHookDependencies", true);
        }
        if (!reactConfig.containsKey("extractComponents") || !(reactConfig.get("extractComponents") instanceof Boolean)) {
            reactConfig.put("extractComponents", true);
        }
        if (!reactConfig.containsKey("jsxLineBreakRule") || !(reactConfig.get("jsxLineBreakRule") instanceof String)) {
            reactConfig.put("jsxLineBreakRule", "multiline");
        }
    }

    /**
     * Saves configuration to a file with better error handling.
     */
    public static void saveConfig(FormatterConfig config, Path configPath) throws IOException {
        try {
            Path parent = configPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Map<String, Object> configMap = new HashMap<>();
            configMap.put("general", config.getGeneralConfigMap());
            configMap.put("plugins", config.getPluginConfigsMap());

            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.writeValue(configPath.toFile(), configMap);

            logger.info("Configuration saved successfully to: " + configPath);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save configuration to: " + configPath, e);
            throw e;
        }
    }
}