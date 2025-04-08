package com.codeformatter.config;

import java.util.HashMap;
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

    /**
     * Gets a copy of the general config map.
     */
    public Map<String, Object> getGeneralConfigMap() {
        return new HashMap<>(generalConfig);
    }

    /**
     * Gets a copy of the plugin configs map.
     */
    public Map<String, Map<String, Object>> getPluginConfigsMap() {
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : pluginConfigs.entrySet()) {
            result.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return result;
    }
    
    @SuppressWarnings("unchecked")
    public <T> T getGeneralConfig(String key, T defaultValue) {
        Object value = generalConfig.get(key);
        return value != null ? (T) value : defaultValue;
    }
    
    @SuppressWarnings("unchecked")
    public <T> T getPluginConfig(String plugin, String key, T defaultValue) {
        try {
            Map<String, Object> pluginConfig = pluginConfigs.get(plugin);
            if (pluginConfig == null) {
                return defaultValue;
            }

            Object value = pluginConfig.get(key);
            if (value == null) {
                return defaultValue;
            }

            if (defaultValue != null && !defaultValue.getClass().isInstance(value)) {

                if (defaultValue instanceof Integer && value instanceof Number) {
                    return (T) Integer.valueOf(((Number) value).intValue());
                } else if (defaultValue instanceof Boolean && value instanceof String) {
                    return (T) Boolean.valueOf(value.toString());
                } else if (defaultValue instanceof String) {
                    return (T) value.toString();
                }

                return defaultValue;
            }

            return (T) value;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}