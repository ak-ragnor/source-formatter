package com.codeformatter.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Tests for configuration loading and validation.
 */
public class ConfigurationLoaderTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Test loading default configuration")
    public void testLoadDefaultConfig() {
        FormatterConfig config = ConfigurationLoader.loadDefaultConfig();

        // Verify general config
        assertNotNull(config, "Config should not be null");

        Map<String, Object> generalConfig = config.getGeneralConfigMap();
        assertNotNull(generalConfig, "General config should not be null");
        assertTrue(generalConfig.containsKey("indentSize"), "Should have indentSize");
        assertTrue(generalConfig.containsKey("lineLength"), "Should have lineLength");

        // Verify plugin configs
        Map<String, Map<String, Object>> pluginConfigs = config.getPluginConfigsMap();
        assertNotNull(pluginConfigs, "Plugin configs should not be null");
        assertTrue(pluginConfigs.containsKey("spring"), "Should have spring config");
        assertTrue(pluginConfigs.containsKey("react"), "Should have react config");

        // Check spring config values
        assertEquals(50, config.getPluginConfig("spring", "maxMethodLines", 0),
                "Default maxMethodLines should be 50");
        assertEquals(15, config.getPluginConfig("spring", "maxMethodComplexity", 0),
                "Default maxMethodComplexity should be 15");

        // Check react config values
        assertEquals(150, config.getPluginConfig("react", "maxComponentLines", 0),
                "Default maxComponentLines should be 150");
        assertEquals(true, config.getPluginConfig("react", "enforceHookDependencies", false),
                "Default enforceHookDependencies should be true");
    }

    @Test
    @DisplayName("Test loading configuration from file")
    public void testLoadConfigFromFile() throws Exception {
        // Create a test config file
        Path configFile = tempDir.resolve("test-config.yml");
        Files.writeString(configFile,
                "general:\n" +
                        "  indentSize: 2\n" +
                        "  lineLength: 80\n" +
                        "  useTabs: true\n" +
                        "plugins:\n" +
                        "  spring:\n" +
                        "    maxMethodLines: 30\n" +
                        "    maxMethodComplexity: 10\n" +
                        "  react:\n" +
                        "    maxComponentLines: 100\n" +
                        "    enforceHookDependencies: false\n");

        // Load the config
        FormatterConfig config = ConfigurationLoader.loadConfig(configFile);

        // Verify general config
        assertEquals(2, config.getGeneralConfig("indentSize", 0),
                "indentSize should be 2");
        assertEquals(80, config.getGeneralConfig("lineLength", 0),
                "lineLength should be 80");
        assertEquals(true, config.getGeneralConfig("useTabs", false),
                "useTabs should be true");

        // Verify spring config
        assertEquals(30, config.getPluginConfig("spring", "maxMethodLines", 0),
                "maxMethodLines should be 30");
        assertEquals(10, config.getPluginConfig("spring", "maxMethodComplexity", 0),
                "maxMethodComplexity should be 10");

        // Verify react config
        assertEquals(100, config.getPluginConfig("react", "maxComponentLines", 0),
                "maxComponentLines should be 100");
        assertEquals(false, config.getPluginConfig("react", "enforceHookDependencies", true),
                "enforceHookDependencies should be false");
    }

    @Test
    @DisplayName("Test loading configuration with invalid values")
    public void testLoadConfigWithInvalidValues() throws Exception {
        // Create a test config file with invalid values
        Path configFile = tempDir.resolve("invalid-config.yml");
        Files.writeString(configFile,
                "general:\n" +
                        "  indentSize: 20\n" + // Too large, should be clamped
                        "  lineLength: 10\n" + // Too small, should be clamped
                        "plugins:\n" +
                        "  spring:\n" +
                        "    maxMethodLines: 1000\n" + // Too large, should be clamped
                        "    maxMethodComplexity: 0\n"); // Too small, should be clamped

        // Load the config
        FormatterConfig config = ConfigurationLoader.loadConfig(configFile);

        // Values should be capped to reasonable defaults
        assertNotEquals(20, config.getGeneralConfig("indentSize", 4),
                "indentSize should not be 20");
        assertNotEquals(10, config.getGeneralConfig("lineLength", 100),
                "lineLength should not be 10");

        // Plugin configs should have reasonable values
        assertTrue(config.getPluginConfig("spring", "maxMethodLines", 50) >= 10,
                "maxMethodLines should be at least 10");
        assertTrue(config.getPluginConfig("spring", "maxMethodComplexity", 15) >= 1,
                "maxMethodComplexity should be at least 1");
    }

    @Test
    @DisplayName("Test handling missing configuration file")
    public void testHandleMissingConfigFile() {
        // Try to load a non-existent config file
        Path nonExistentFile = tempDir.resolve("non-existent.yml");
        FormatterConfig config = ConfigurationLoader.loadConfig(nonExistentFile);

        // Should fall back to default configuration
        assertNotNull(config, "Config should not be null");

        // Verify it has default values
        assertEquals(4, config.getGeneralConfig("indentSize", 0),
                "Should have default indentSize");
        assertEquals(100, config.getGeneralConfig("lineLength", 0),
                "Should have default lineLength");
        assertEquals(50, config.getPluginConfig("spring", "maxMethodLines", 0),
                "Should have default maxMethodLines");
    }

    @Test
    @DisplayName("Test saving configuration to file")
    public void testSaveConfigToFile() throws Exception {
        // Create a configuration
        Map<String, Object> generalConfig = Map.of(
                "indentSize", 2,
                "lineLength", 80,
                "useTabs", true
        );

        Map<String, Object> springConfig = Map.of(
                "maxMethodLines", 30,
                "maxMethodComplexity", 10
        );

        Map<String, Object> reactConfig = Map.of(
                "maxComponentLines", 100,
                "enforceHookDependencies", false
        );

        Map<String, Map<String, Object>> pluginConfigs = Map.of(
                "spring", springConfig,
                "react", reactConfig
        );

        FormatterConfig config = new FormatterConfig(generalConfig, pluginConfigs);

        // Save the config to a file
        Path outputFile = tempDir.resolve("output-config.yml");
        ConfigurationLoader.saveConfig(config, outputFile);

        // Verify the file was created
        assertTrue(Files.exists(outputFile), "Config file should exist");

        // Load the saved config to verify it
        FormatterConfig loadedConfig = ConfigurationLoader.loadConfig(outputFile);

        // Verify the loaded config has the same values
        assertEquals(2, loadedConfig.getGeneralConfig("indentSize", 0),
                "indentSize should be preserved");
        assertEquals(80, loadedConfig.getGeneralConfig("lineLength", 0),
                "lineLength should be preserved");
        assertEquals(true, loadedConfig.getGeneralConfig("useTabs", false),
                "useTabs should be preserved");

        assertEquals(30, loadedConfig.getPluginConfig("spring", "maxMethodLines", 0),
                "spring.maxMethodLines should be preserved");
        assertEquals(10, loadedConfig.getPluginConfig("spring", "maxMethodComplexity", 0),
                "spring.maxMethodComplexity should be preserved");

        assertEquals(100, loadedConfig.getPluginConfig("react", "maxComponentLines", 0),
                "react.maxComponentLines should be preserved");
        assertEquals(false, loadedConfig.getPluginConfig("react", "enforceHookDependencies", true),
                "react.enforceHookDependencies should be preserved");
    }

    @Test
    @DisplayName("Test configuration with arrays and complex structures")
    public void testConfigWithArrays() throws Exception {
        // Create a test config file with arrays
        Path configFile = tempDir.resolve("array-config.yml");
        Files.writeString(configFile,
                "general:\n" +
                        "  indentSize: 2\n" +
                        "  ignoreFiles:\n" +
                        "    - \"**/*.min.js\"\n" +
                        "    - \"**/node_modules/**\"\n" +
                        "    - \"**/build/**\"\n" +
                        "plugins:\n" +
                        "  spring:\n" +
                        "    importOrganization:\n" +
                        "      groups:\n" +
                        "        - static\n" +
                        "        - java\n" +
                        "        - org.springframework\n" +
                        "        - com\n" +
                        "        - org\n");

        // Load the config
        FormatterConfig config = ConfigurationLoader.loadConfig(configFile);

        // Verify lists are loaded
        @SuppressWarnings("unchecked")
        List<String> ignoreFiles = config.getGeneralConfig("ignoreFiles", List.of());
        assertNotNull(ignoreFiles, "ignoreFiles should not be null");
        assertEquals(3, ignoreFiles.size(), "Should have 3 ignoreFiles entries");
        assertTrue(ignoreFiles.contains("**/*.min.js"), "Should contain *.min.js pattern");

        // Verify nested structures
        @SuppressWarnings("unchecked")
        List<String> importGroups = config.getPluginConfig("spring", "importOrganization.groups", List.of());
        assertNotNull(importGroups, "Import groups should not be null");
        assertEquals(5, importGroups.size(), "Should have 5 import groups");
        assertEquals("static", importGroups.get(0), "First group should be static");
        assertEquals("java", importGroups.get(1), "Second group should be java");
    }

    @Test
    @DisplayName("Test configuration with invalid YAML")
    public void testInvalidYaml() throws Exception {
        // Create a test config file with invalid YAML
        Path configFile = tempDir.resolve("invalid-yaml.yml");
        Files.writeString(configFile,
                "general:\n" +
                        "  indentSize: 2\n" +
                        "plugins:\n" +
                        "  spring:\n" +
                        "    maxMethodLines: 30\n" +
                        "  This is not valid YAML content\n" +
                        "    more invalid content");

        // Load the config - should fall back to defaults
        FormatterConfig config = ConfigurationLoader.loadConfig(configFile);

        // Should get the default configuration
        assertNotNull(config, "Config should not be null");
        assertTrue(config.getGeneralConfigMap().containsKey("indentSize"),
                "Should have default indentSize");
    }
}