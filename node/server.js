const express = require('express');
const prettier = require('prettier');
const { ESLint } = require('eslint');
const path = require('path');
const fs = require('fs');

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

// For health check and startup verification
app.get('/health', (req, res) => {
  res.json({ status: 'ok', version: '1.0.0' });
});

// Global configuration storage
let globalConfig = {
  prettier: {
    printWidth: 80,
    tabWidth: 2,
    useTabs: false,
    semi: true,
    singleQuote: true,
    trailingComma: 'es5',
    bracketSpacing: true,
    jsxBracketSameLine: false,
    arrowParens: 'avoid',
  },
  eslint: {
    rules: {},
  },
};

// Configuration endpoint
app.post('/configure', (req, res) => {
  try {
    const config = req.body;

    // Update global configuration
    if (config.prettier) {
      globalConfig.prettier = { ...globalConfig.prettier, ...config.prettier };
    }

    if (config.eslint) {
      globalConfig.eslint = {
        ...globalConfig.eslint,
        rules: {
          ...(globalConfig.eslint.rules || {}),
          ...(config.eslint.rules || {}),
        },
      };
    }

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

// Format code endpoint
app.post('/format', async (req, res) => {
  try {
    const { code, isReact, options } = req.body;

    if (!code) {
      return res.status(400).json({
        success: false,
        error: 'No code provided',
      });
    }

    // Merge global configuration with request-specific options
    const prettierOptions = {
      // Start with global config
      ...globalConfig.prettier,
      // Set parser based on file type
      parser: isReact ? 'babel' : 'babel',
      // Override with request-specific options if provided
      ...(options || {}),
    };

    // Try to format with prettier
    let formattedCode;
    try {
      formattedCode = prettier.format(code, prettierOptions);
    } catch (prettierError) {
      console.warn('Prettier formatting failed:', prettierError.message);
      // Return original code if prettier fails
      formattedCode = code;
    }

    res.json({
      success: true,
      formattedCode,
    });
  } catch (error) {
    console.error('Formatting error:', error);
    res.status(500).json({
      success: false,
      error: error.message,
      originalCode: req.body.code,
    });
  }
});

// Analyze code endpoint
app.post('/analyze', async (req, res) => {
  try {
    const { code, isReact, eslintConfigPath } = req.body;

    if (!code) {
      return res.status(400).json({
        success: false,
        error: 'No code provided',
      });
    }

    // Create a temporary file
    const timestamp = Date.now();
    const tempFile = path.join(tempDir, `temp-${timestamp}.${isReact ? 'jsx' : 'js'}`);
    fs.writeFileSync(tempFile, code);

    // ESLint config options
    const eslintOptions = {
      fix: true, // Always enable auto-fixing
      extensions: ['.js', '.jsx', '.ts', '.tsx'],
      resolvePluginsRelativeTo: __dirname // Use the server directory to resolve plugins
    };

    // If a specific config path is provided, use it
    if (eslintConfigPath) {
      eslintOptions.overrideConfigFile = eslintConfigPath;
    } else {
      // Look for .eslintrc.js in the current directory
      const localConfigPath = path.join(__dirname, '.eslintrc.js');
      if (fs.existsSync(localConfigPath)) {
        eslintOptions.overrideConfigFile = localConfigPath;
      }
    }

    try {
      // Initialize ESLint with our config
      const eslint = new ESLint(eslintOptions);

      // Run ESLint
      const results = await eslint.lintFiles([tempFile]);

      // Get fixed code (if fixes were applied)
      let fixedCode = code;
      if (results[0].output) {
        fixedCode = results[0].output;
        // Read the fixed file
        fixedCode = fs.readFileSync(tempFile, 'utf8');
      }

      // Clean up temporary file
      fs.unlinkSync(tempFile);

      // Format results
      const issues = results[0].messages.map(msg => ({
        ruleId: msg.ruleId || 'syntax-error',
        severity: msg.severity === 2 ? 'error' : 'warning',
        message: msg.message,
        line: msg.line || 1,
        column: msg.column || 1,
      }));

      res.json({
        success: true,
        issues,
        fixedCode: fixedCode // Include the fixed code in the response
      });
    } catch (lintError) {
      // Clean up and return error
      try {
        fs.unlinkSync(tempFile);
      } catch (e) {
        // Ignore cleanup errors
      }

      console.error('ESLint error:', lintError);
      res.status(500).json({
        success: false,
        error: lintError.message,
      });
    }
  } catch (error) {
    console.error('Analysis error:', error);
    res.status(500).json({
      success: false,
      error: error.message,
    });
  }
});

// Start server
const server = app.listen(port, () => {
  console.log(`Server listening on port ${port}`);
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