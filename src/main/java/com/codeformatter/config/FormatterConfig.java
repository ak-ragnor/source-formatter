package com.codeformatter.config;

import java.util.Map;

/**
 * Configuration for the formatter.
 */
public class FormatterConfig {
    private final Map<String, Object> generalConfig;
    private final Map<String, Map<String, Object>> pluginConfigs;
    
    public FormatterConfig(Map<String, Object> generalConfig, 
                          Map<String, Map<String, Object>> pluginConfigs) {
        this.generalConfig = generalConfig;
        this.pluginConfigs = pluginConfigs;
    }
    
    @SuppressWarnings("unchecked")
    public <T> T getGeneralConfig(String key, T defaultValue) {
        Object value = generalConfig.get(key);
        return value != null ? (T) value : defaultValue;
    }
    
    @SuppressWarnings("unchecked")
    public <T> T getPluginConfig(String plugin, String key, T defaultValue) {
        Map<String, Object> pluginConfig = pluginConfigs.get(plugin);
        if (pluginConfig == null) {
            return defaultValue;
        }
        
        Object value = pluginConfig.get(key);
        return value != null ? (T) value : defaultValue;
    }
}