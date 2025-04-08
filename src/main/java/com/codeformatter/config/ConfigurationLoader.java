package com.codeformatter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Enhanced configuration loader with better error handling and validation.
 */
public class ConfigurationLoader {
    private static final Logger logger = Logger.getLogger(ConfigurationLoader.class.getName());
    private static final String DEFAULT_CONFIG_RESOURCE = "/config/default-config.yml";

    /**
     * Loads configuration from a file with fallback to defaults.
     */
    public static FormatterConfig loadConfig(Path configPath) {
        if (configPath == null) {
            logger.warning("No config path provided, using default configuration");
            return _createDefaultConfig();
        }

        if (!Files.exists(configPath)) {
            logger.warning("Configuration file not found: " + configPath + ", using default configuration");
            return _createDefaultConfig();
        }

        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> config = mapper.readValue(configPath.toFile(), Map.class);

            return _createConfigFromMap(config);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error parsing configuration file: " + e.getMessage(), e);
            logger.info("Falling back to default configuration");
            return _createDefaultConfig();
        }
    }

    /**
     * Loads the embedded default configuration.
     */
    public static FormatterConfig loadDefaultConfig() {
        try (InputStream defaultConfigStream =
                     ConfigurationLoader.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {

            if (defaultConfigStream == null) {
                logger.severe("Default configuration resource not found: " + DEFAULT_CONFIG_RESOURCE);
                return _createEmptyConfig();
            }

            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> config = mapper.readValue(defaultConfigStream, Map.class);

            return _createConfigFromMap(config);
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
        // Extract general config with validation
        Map<String, Object> generalConfig = new HashMap<>();
        if (config.containsKey("general") && config.get("general") instanceof Map) {
            generalConfig = (Map<String, Object>) config.get("general");
        } else {
            logger.warning("Missing or invalid 'general' section in config, using defaults");
        }

        // Ensure required general config values exist
        _ensureDefaultGeneralConfig(generalConfig);

        // Extract plugin configs with validation
        Map<String, Map<String, Object>> pluginConfigs = new HashMap<>();
        if (config.containsKey("plugins") && config.get("plugins") instanceof Map) {
            Map<String, Object> pluginsMap = (Map<String, Object>) config.get("plugins");

            for (Map.Entry<String, Object> entry : pluginsMap.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    pluginConfigs.put(entry.getKey(), (Map<String, Object>) entry.getValue());
                } else {
                    logger.warning("Invalid configuration for plugin '" + entry.getKey() + "', using defaults");
                    pluginConfigs.put(entry.getKey(), new HashMap<>());
                }
            }
        } else {
            logger.warning("Missing or invalid 'plugins' section in config, using defaults");
        }

        // Ensure all plugin configs have required values
        _ensureDefaultPluginConfigs(pluginConfigs);

        return new FormatterConfig(generalConfig, pluginConfigs);
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
     * Creates a hardcoded default configuration.
     */
    private static FormatterConfig _createDefaultConfig() {
        // Try to load from the embedded default first
        FormatterConfig config = loadDefaultConfig();
        if (config != null) {
            return config;
        }

        // Fall back to hardcoded defaults if needed
        Map<String, Object> generalConfig = new HashMap<>();
        generalConfig.put("indentSize", 4);
        generalConfig.put("tabWidth", 4);
        generalConfig.put("useTabs", false);
        generalConfig.put("lineLength", 100);
        generalConfig.put("ignoreFiles", new ArrayList<String>());

        Map<String, Object> springConfig = new HashMap<>();
        springConfig.put("maxMethodLines", 50);
        springConfig.put("maxMethodComplexity", 15);
        springConfig.put("enforceDesignPatterns", true);
        springConfig.put("enforceDependencyInjection", "constructor");

        Map<String, Object> reactConfig = new HashMap<>();
        reactConfig.put("maxComponentLines", 150);
        reactConfig.put("enforceHookDependencies", true);
        reactConfig.put("extractComponents", true);
        reactConfig.put("jsxLineBreakRule", "multiline");

        Map<String, Map<String, Object>> pluginConfigs = new HashMap<>();
        pluginConfigs.put("spring", springConfig);
        pluginConfigs.put("react", reactConfig);

        return new FormatterConfig(generalConfig, pluginConfigs);
    }

    /**
     * Ensures that general configuration has all required default values.
     */
    private static void _ensureDefaultGeneralConfig(Map<String, Object> generalConfig) {
        // Set defaults if missing
        if (!generalConfig.containsKey("indentSize")) {
            generalConfig.put("indentSize", 4);
        }
        if (!generalConfig.containsKey("tabWidth")) {
            generalConfig.put("tabWidth", 4);
        }
        if (!generalConfig.containsKey("useTabs")) {
            generalConfig.put("useTabs", false);
        }
        if (!generalConfig.containsKey("lineLength")) {
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
        // Set defaults for Spring plugin
        Map<String, Object> springConfig = pluginConfigs.computeIfAbsent("spring", k -> new HashMap<>());
        if (!springConfig.containsKey("maxMethodLines")) {
            springConfig.put("maxMethodLines", 50);
        }
        if (!springConfig.containsKey("maxMethodComplexity")) {
            springConfig.put("maxMethodComplexity", 15);
        }
        if (!springConfig.containsKey("enforceDesignPatterns")) {
            springConfig.put("enforceDesignPatterns", true);
        }
        if (!springConfig.containsKey("enforceDependencyInjection")) {
            springConfig.put("enforceDependencyInjection", "constructor");
        }

        // Set defaults for React plugin
        Map<String, Object> reactConfig = pluginConfigs.computeIfAbsent("react", k -> new HashMap<>());
        if (!reactConfig.containsKey("maxComponentLines")) {
            reactConfig.put("maxComponentLines", 150);
        }
        if (!reactConfig.containsKey("enforceHookDependencies")) {
            reactConfig.put("enforceHookDependencies", true);
        }
        if (!reactConfig.containsKey("extractComponents")) {
            reactConfig.put("extractComponents", true);
        }
        if (!reactConfig.containsKey("jsxLineBreakRule")) {
            reactConfig.put("jsxLineBreakRule", "multiline");
        }
    }

    /**
     * Saves configuration to a file.
     */
    public static void saveConfig(FormatterConfig config, Path configPath) throws IOException {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("general", config.getGeneralConfigMap());
        configMap.put("plugins", config.getPluginConfigsMap());

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.writeValue(configPath.toFile(), configMap);
    }
}
