package com.codeformatter.plugins.react;

import com.codeformatter.api.FormatterPlugin;
import com.codeformatter.config.FormatterConfig;
import com.codeformatter.util.LoggerUtil;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Factory for creating React formatter instances based on config or system properties. This allows
 * for a smooth transition between the old embedded JS engine and the new Node.js approach.
 */
public class ReactFormatterFactory {
  private static final Logger logger = LoggerUtil.getLogger(ReactFormatterFactory.class);

  // System property to control formatter implementation
  private static final String FORMATTER_IMPL_PROPERTY = "codeformatter.react.implementation";

  // Implementation type enum
  public enum ImplementationType {
    NODEJS, // New Node.js based implementation
    EMBEDDED, // Old embedded JS engine implementation (to be deprecated)
    AUTO // Automatic selection based on availability
  }

  /** Create a React formatter instance based on configuration or system properties. */
  public static FormatterPlugin createFormatter(FormatterConfig config) {
    ImplementationType type = getImplementationType(config);

    switch (type) {
      case NODEJS:
        logger.info("Using Node.js based React formatter implementation");
        return new ReactJSFormatter();

      case EMBEDDED:
        logger.warning(
            "Using deprecated embedded JS engine React formatter implementation. "
                + "This will be removed in a future release.");
        return new LegacyReactJSFormatter();

      case AUTO:
      default:
        // Try Node.js first, fall back to embedded if necessary
        try {
          logger.info("Attempting to use Node.js based React formatter implementation");
          return new ReactJSFormatter();
        } catch (Exception e) {
          logger.log(
              Level.WARNING,
              "Failed to initialize Node.js formatter, falling back to embedded JS engine: "
                  + e.getMessage(),
              e);
          return new LegacyReactJSFormatter();
        }
    }
  }

  /** Determine which implementation type to use based on config and system properties. */
  private static ImplementationType getImplementationType(FormatterConfig config) {
    // Check system property first
    String implProperty = System.getProperty(FORMATTER_IMPL_PROPERTY);
    if (implProperty != null) {
      try {
        return ImplementationType.valueOf(implProperty.toUpperCase());
      } catch (IllegalArgumentException e) {
        logger.warning("Invalid formatter implementation property: " + implProperty);
      }
    }

    // Check configuration
    String implConfig = config.getPluginConfig("react", "formatterImplementation", "AUTO");
    try {
      return ImplementationType.valueOf(implConfig.toUpperCase());
    } catch (IllegalArgumentException e) {
      logger.warning("Invalid formatter implementation in config: " + implConfig);
      return ImplementationType.AUTO;
    }
  }

  /**
   * Rename the old ReactJSFormatter to LegacyReactJSFormatter for backwards compatibility. This is
   * a placeholder - in your actual code, you would copy the old implementation here.
   */
  private static class LegacyReactJSFormatter implements FormatterPlugin, AutoCloseable {
    // This would be your old ReactJSFormatter implementation using the embedded JS engine
    // For now, we'll just throw an exception as we're completely replacing it

    @Override
    public void initialize(FormatterConfig config) {
      throw new UnsupportedOperationException(
          "Legacy React formatter implementation is no longer supported");
    }

    @Override
    public com.codeformatter.api.FormatterResult format(Path filePath, String sourceCode) {
      throw new UnsupportedOperationException(
          "Legacy React formatter implementation is no longer supported");
    }

    @Override
    public void close() {
      // Nothing to do
    }
  }
}
