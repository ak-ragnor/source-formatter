const express = require('express');
const { ESLint } = require('eslint');
const path = require('path');
const fs = require('fs');
// Fix for lru-cache v10.x
const { LRUCache } = require('lru-cache');

// Get port from command line or use default
const port = process.argv[2] || 9567;
const app = express();

// Create temp directory if it doesn't exist
const tempDir = path.join(__dirname, 'temp');
if (!fs.existsSync(tempDir)) {
  fs.mkdirSync(tempDir, { recursive: true });
}

// For parsing application/json
app.use(express.json({ limit: '50mb' }));

// Add a cache for ESLint results to improve performance
const eslintResultCache = new LRUCache({
  max: 100,
  ttl: 1000 * 60 * 10, // 10 minutes
});

// For health check and startup verification
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    version: '1.0.0',
    mode: 'eslint-with-prettier',
  });
});

// Global configuration storage
let globalConfig = {
  eslint: {
    rules: {
      // Default rules when none are provided
      'prettier/prettier': [
        'error',
        {
          printWidth: 100,
          tabWidth: 2,
          useTabs: false,
          semi: true,
          singleQuote: true,
          trailingComma: 'es5',
          bracketSpacing: true,
          jsxBracketSameLine: false,
          arrowParens: 'avoid',
        },
      ],
    },
  },
};

// Configuration endpoint
app.post('/configure', (req, res) => {
  try {
    const config = req.body;

    // Update global configuration
    if (config.eslint) {
      globalConfig.eslint = {
        ...globalConfig.eslint,
        rules: {
          ...(globalConfig.eslint.rules || {}),
          ...(config.eslint.rules || {}),
        },
      };
    }

    // Update prettier config within ESLint
    if (config.prettier) {
      globalConfig.eslint.rules = {
        ...globalConfig.eslint.rules,
        'prettier/prettier': ['error', config.prettier],
      };
    }

    // Clear cache when config changes
    eslintResultCache.clear();

    console.log('Configuration updated');
    res.json({ success: true, message: 'Configuration updated' });
  } catch (error) {
    console.error('Error updating configuration:', error);
    res.status(500).json({
      success: false,
      error: error.message,
    });
  }
});

// Process code with ESLint (core function used by all endpoints)
async function processWithESLint(code, isReact) {
  // Create a temporary file with the correct extension
  const timestamp = Date.now();
  const tempFile = path.join(
    tempDir,
    `temp-${timestamp}.${isReact ? 'jsx' : 'js'}`
  );
  fs.writeFileSync(tempFile, code);

  try {
    // Initialize ESLint with our config
    const eslintOptions = {
      fix: true, // Enable auto-fixing (for prettier formatting)
      extensions: ['.js', '.jsx', '.ts', '.tsx'],
      resolvePluginsRelativeTo: __dirname, // Use the server directory to resolve plugins
      overrideConfig: {
        ...globalConfig.eslint,
        parser: isReact ? '@babel/eslint-parser' : '@babel/eslint-parser',
        parserOptions: {
          ecmaVersion: 'latest',
          sourceType: 'module',
          ecmaFeatures: {
            jsx: isReact,
          },
          requireConfigFile: false,
          babelOptions: {
            presets: ['@babel/preset-react'],
          },
        },
        plugins: ['prettier', 'react', 'react-hooks'],
        extends: [
          'eslint:recommended',
          'plugin:react/recommended',
          'plugin:react-hooks/recommended',
          'prettier',
        ],
      },
    };

    // Look for .eslintrc.js in the current directory
    const localConfigPath = path.join(__dirname, '.eslintrc.js');
    if (fs.existsSync(localConfigPath)) {
      eslintOptions.overrideConfigFile = localConfigPath;
    }

    // Create ESLint instance
    const eslint = new ESLint(eslintOptions);

    // Run ESLint and get results
    const results = await eslint.lintFiles([tempFile]);

    // Get the formatted code
    let formattedCode;
    if (results[0].output) {
      formattedCode = results[0].output;
    } else {
      // If ESLint didn't make any changes, read back the original file
      formattedCode = code;
    }

    // Get suggested fixes from ESLint
    const formatter = await eslint.loadFormatter('json');
    const formattedResults = JSON.parse(formatter.format(results));

    // Format the linting issues and include ESLint's suggestions
    const issues = results[0].messages.map(msg => {
      // Extract suggestions directly from ESLint when available
      let suggestion = null;

      // Some rules provide fix information
      if (msg.fix) {
        suggestion = `ESLint can automatically fix this issue with the '--fix' option.`;
      }
      // Some rules provide direct suggestions
      else if (msg.suggestions && msg.suggestions.length > 0) {
        suggestion =
          msg.suggestions[0].desc ||
          `Suggested fix: ${
            msg.suggestions[0].messageId || 'Apply ESLint suggestion'
          }`;
      }
      // For common rules, extract from the message or use common patterns
      else {
        suggestion = extractSuggestionFromMessage(
          msg.ruleId,
          msg.message,
          isReact
        );
      }

      return {
        ruleId: msg.ruleId || 'syntax-error',
        severity: msg.severity === 2 ? 'error' : 'warning',
        message: msg.message,
        line: msg.line || 1,
        column: msg.column || 1,
        suggestion,
        fixable:
          msg.fix !== undefined ||
          (msg.suggestions && msg.suggestions.length > 0),
      };
    });

    // Clean up temporary file
    try {
      fs.unlinkSync(tempFile);
    } catch (e) {
      console.warn('Could not delete temp file:', e.message);
    }

    return {
      success: true,
      formattedCode,
      issues,
      fixableIssueCount: issues.filter(issue => issue.fixable).length,
    };
  } catch (error) {
    // Clean up and re-throw
    try {
      fs.unlinkSync(tempFile);
    } catch (e) {
      // Ignore cleanup errors
    }
    throw error;
  }
}

// Combined format and analyze endpoint
app.post('/format-and-analyze', async (req, res) => {
  try {
    const { code, isReact, options, cacheKey } = req.body;

    if (!code) {
      return res.status(400).json({
        success: false,
        error: 'No code provided',
      });
    }

    // Check cache first if cache key provided
    if (cacheKey && eslintResultCache.has(cacheKey)) {
      console.log('Cache hit for', cacheKey);
      return res.json(eslintResultCache.get(cacheKey));
    }

    const result = await processWithESLint(code, isReact);

    // Cache the result if cache key provided
    if (cacheKey) {
      eslintResultCache.set(cacheKey, result);
    }

    res.json(result);
  } catch (error) {
    console.error('Processing error:', error);
    res.status(500).json({
      success: false,
      error: error.message,
      originalCode: req.body.code,
    });
  }
});

// Helper function to extract a suggestion from the ESLint message
function extractSuggestionFromMessage(ruleId, message, isReact) {
  if (!ruleId) return null;

  // Look for common ESLint message patterns that contain suggestions
  if (message.includes('is defined but never used')) {
    return 'Remove this unused variable or use it in your code.';
  }

  if (message.includes('must be placed on a new line')) {
    return 'Move this content to a new line to follow style guidelines.';
  }

  if (message.includes('Replace') && message.includes('with')) {
    // Extract replacement suggestion directly from message
    const match = message.match(
      /Replace\s+['"`](.*?)['"`]\s+with\s+['"`](.*?)['"`]/
    );
    if (match) {
      return `Replace '${match[1]}' with '${match[2]}'.`;
    }
  }

  if (message.includes('is missing in props validation')) {
    return "Add this prop to the component's PropTypes definition.";
  }

  // React Hook rules
  if (ruleId === 'react-hooks/exhaustive-deps') {
    // Extract missing dependencies
    const depMatch = message.match(
      /React Hook \w+ has (?:a )?missing dependenc(?:y|ies): ['"](.+?)['"]/
    );
    if (depMatch) {
      return `Add ${depMatch[1]} to the dependency array. When this value changes, your effect should re-run.`;
    }
    return 'Add all dependencies used in the hook to its dependency array.';
  }

  if (ruleId === 'react-hooks/rules-of-hooks') {
    return 'React Hooks must be called at the top level of your component or custom hook. They cannot be called inside loops, conditions, or nested functions.';
  }

  // Handle prettier/prettier rules
  if (ruleId === 'prettier/prettier') {
    return 'Format this code according to Prettier rules. This can be automatically fixed.';
  }

  // Default fallback based on rule ID
  if (ruleId.startsWith('react/')) {
    return 'This violates a React best practice. Check the React documentation for recommended patterns.';
  }

  if (ruleId.startsWith('import/')) {
    return "Fix this import statement according to the project's import organization rules.";
  }

  // No specific suggestion available
  return null;
}

// Legacy endpoints for backward compatibility - implemented directly
// Format endpoint (formatting only)
app.post('/format', async (req, res) => {
  try {
    const { code, isReact, options } = req.body;

    if (!code) {
      return res.status(400).json({
        success: false,
        error: 'No code provided',
      });
    }

    // Process the code using the shared function
    const result = await processWithESLint(code, isReact);

    // Return only the formatting result
    res.json({
      success: true,
      formattedCode: result.formattedCode,
    });
  } catch (error) {
    console.error('Format endpoint error:', error);
    res.status(500).json({
      success: false,
      error: error.message,
    });
  }
});

// Analyze endpoint (linting only)
app.post('/analyze', async (req, res) => {
  try {
    const { code, isReact, options } = req.body;

    if (!code) {
      return res.status(400).json({
        success: false,
        error: 'No code provided',
      });
    }

    // Process the code using the shared function
    const result = await processWithESLint(code, isReact);

    // Return only the analysis results
    res.json({
      success: true,
      issues: result.issues,
    });
  } catch (error) {
    console.error('Analyze endpoint error:', error);
    res.status(500).json({
      success: false,
      error: error.message,
    });
  }
});

// Try to start server with port handling
console.log(`Attempting to start server on port ${port}...`);
const server = app.listen(port, () => {
  console.log(
    `Server listening on port ${port} with ESLint+Prettier integration`
  );
});

// Handle server startup errors
server.on('error', error => {
  console.error('Server startup error:', error.message);
  if (error.code === 'EADDRINUSE') {
    console.error(`Port ${port} is already in use. Try another port.`);
  }
  process.exit(1);
});

// Handle graceful shutdown
process.on('SIGTERM', () => {
  console.log('Received SIGTERM, shutting down');
  server.close(() => {
    console.log('Server stopped');
    process.exit(0);
  });
});

process.on('SIGINT', () => {
  console.log('Received SIGINT, shutting down');
  server.close(() => {
    console.log('Server stopped');
    process.exit(0);
  });
});
