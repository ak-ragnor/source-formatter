package com.codeformatter.plugins.react;

import com.codeformatter.config.FormatterConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridges Java configuration to JavaScript tools configuration. This class handles the conversion
 * of Java-side settings to the appropriate format for Prettier and ESLint.
 */
public class ConfigurationBridge {
  private static final ObjectMapper objectMapper = new ObjectMapper();

  /** Create Prettier configuration from Java FormatterConfig. */
  public static Map<String, Object> createPrettierConfig(FormatterConfig javaConfig) {
    Map<String, Object> prettierConfig = new HashMap<>();

    // General formatting options
    prettierConfig.put("printWidth", javaConfig.getGeneralConfig("lineLength", 100));
    prettierConfig.put("tabWidth", javaConfig.getGeneralConfig("indentSize", 2));
    prettierConfig.put("useTabs", javaConfig.getGeneralConfig("useTabs", false));

    // Code style options
    prettierConfig.put("semi", true);
    prettierConfig.put("singleQuote", true);
    prettierConfig.put("trailingComma", "es5");
    prettierConfig.put("bracketSpacing", true);

    // React specific options
    prettierConfig.put("jsxBracketSameLine", false);
    prettierConfig.put("jsxSingleQuote", false);
    prettierConfig.put("arrowParens", "avoid");

    // React plugin configuration
    if (javaConfig.getPluginConfigsMap().containsKey("react")) {
      Map<String, Object> reactConfig = javaConfig.getPluginConfigsMap().get("react");

      // Handle JSX line break rules
      String jsxLineBreakRule = (String) reactConfig.getOrDefault("jsxLineBreakRule", "multiline");
      if ("multiline".equals(jsxLineBreakRule)) {
        prettierConfig.put("jsxBracketSameLine", false);
      } else if ("sameline".equals(jsxLineBreakRule)) {
        prettierConfig.put("jsxBracketSameLine", true);
      }
    }

    return prettierConfig;
  }

  /** Create ESLint configuration from Java FormatterConfig. */
  public static Map<String, Object> createEslintConfig(FormatterConfig javaConfig) {
    Map<String, Object> eslintConfig = new HashMap<>();

    // Base configuration - these will override .eslintrc.js settings
    Map<String, Object> rules = new HashMap<>();

    // Code style rules
    rules.put("indent", new Object[] {"warn", javaConfig.getGeneralConfig("indentSize", 2)});
    rules.put("max-len", new Object[] {"warn", javaConfig.getGeneralConfig("lineLength", 100)});

    // React plugin configuration
    if (javaConfig.getPluginConfigsMap().containsKey("react")) {
      Map<String, Object> reactConfig = javaConfig.getPluginConfigsMap().get("react");

      // Component structure rules
      Object maxComponentLines = reactConfig.get("maxComponentLines");
      if (maxComponentLines != null) {
        rules.put(
            "max-lines",
            new Object[] {
              "warn",
              Map.of(
                  "max", maxComponentLines,
                  "skipBlankLines", true,
                  "skipComments", true)
            });
      }

      // Hook dependency checking
      Boolean enforceHookDependencies =
          (Boolean) reactConfig.getOrDefault("enforceHookDependencies", true);
      if (enforceHookDependencies) {
        rules.put("react-hooks/exhaustive-deps", "warn");
      } else {
        rules.put("react-hooks/exhaustive-deps", "off");
      }
    }

    eslintConfig.put("rules", rules);
    return eslintConfig;
  }

  /** Convert configuration to JSON string. */
  public static String toJson(Map<String, Object> config) {
    try {
      return objectMapper.writeValueAsString(config);
    } catch (Exception e) {
      return "{}";
    }
  }

  /** Create a combined configuration for Node.js tools. */
  public static Map<String, Object> createCombinedConfig(FormatterConfig javaConfig) {
    Map<String, Object> combinedConfig = new HashMap<>();

    combinedConfig.put("prettier", createPrettierConfig(javaConfig));
    combinedConfig.put("eslint", createEslintConfig(javaConfig));

    return combinedConfig;
  }
}
