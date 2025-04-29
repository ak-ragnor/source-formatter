module.exports = {
  env: {
    browser: true,
    es2021: true,
    node: true,
    jest: true,
  },
  extends: [
    'airbnb',
    'airbnb/hooks',
    'plugin:react/recommended',
    'plugin:jsx-a11y/recommended',
    'plugin:security/recommended-legacy',
    'plugin:sonarjs/recommended-legacy',
    'plugin:prettier/recommended' // This will enable eslint-plugin-prettier and eslint-config-prettier
  ],
  parser: '@babel/eslint-parser',
  parserOptions: {
    ecmaFeatures: {
      jsx: true,
    },
    ecmaVersion: 'latest',
    sourceType: 'module',
    requireConfigFile: false,
    babelOptions: {
      presets: ['@babel/preset-react']
    }
  },
  plugins: ['react', 'react-hooks', 'jsx-a11y', 'import', 'security', 'prettier'],
  settings: {
    react: {
      version: 'detect',
    },
    'import/resolver': {
      node: {
        extensions: ['.js', '.jsx', '.ts', '.tsx'],
      },
    },
  },
  rules: {
    'prettier/prettier': ['error', {
      printWidth: 100,
      tabWidth: 2,
      useTabs: false,
      semi: true,
      singleQuote: true,
      trailingComma: 'es5',
      bracketSpacing: true,
      jsxBracketSameLine: false,
      arrowParens: 'avoid',
    }],

    // React rules with better suggestions
    'react/prop-types': ['error', {
      skipUndeclared: true
    }],
    'react/react-in-jsx-scope': 'off',
    'react/jsx-filename-extension': [1, { extensions: ['.js', '.jsx'] }],
    'react-hooks/rules-of-hooks': 'error',
    'react-hooks/exhaustive-deps': 'warn',

    // Import rules
    'import/no-extraneous-dependencies': [
      'error',
      {
        devDependencies: [
          '**/*.test.js',
          '**/*.spec.js',
          '**/__tests__/**',
          '**/webpack.config.js',
          '**/jest.config.js',
          '**/cypress/**',
          '**/storybook/**',
          '**/vite.config.js',
        ],
        optionalDependencies: false,
        peerDependencies: false,
      },
    ],

    // Code style
    'no-console': ['warn', { allow: ['warn', 'error'] }],
    'max-len': ['warn', {
      code: 100,
      ignoreComments: true,
      ignoreUrls: true,
      ignoreStrings: true,
      ignoreTemplateLiterals: true
    }],

    // Common issues with better suggestions
    'no-unused-vars': ['warn', {
      vars: 'all',
      args: 'after-used',
      ignoreRestSiblings: true,
      argsIgnorePattern: '^_',
      caughtErrors: 'none'
    }]
  },
  overrides: [
    {
      files: ['*.js', '*.jsx'],
      rules: {
        'react/prop-types': 'error',
      },
    },
    {
      files: ['*.test.js', '*.spec.js', '**/__tests__/**'],
      extends: ['plugin:jest/recommended'],
      plugins: ['jest'],
      rules: {
        'no-unused-expressions': 'off',
        'jest/no-disabled-tests': 'warn',
        'jest/no-focused-tests': 'error',
        'jest/no-identical-title': 'error',
        'jest/prefer-to-have-length': 'warn',
        'jest/valid-expect': 'error',
        'react/display-name': 'off',
      },
    },
    {
      files: [
        '**/webpack.config.js',
        '**/vite.config.js',
        '**/jest.config.js',
        '**/esbuild.config.js',
      ],
      rules: {
        'no-console': 'off',
        'import/no-extraneous-dependencies': 'off',
        'no-process-env': 'off',
      },
    },
  ],
};