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
    'plugin:security/recommended-legacy',
    'plugin:sonarjs/recommended-legacy',
  ],
  parserOptions: {
    ecmaFeatures: {
      jsx: true,
    },
    ecmaVersion: 'latest',
    sourceType: 'module',
  },
  plugins: ['react', 'jsx-a11y', 'import', 'security'],
  settings: {
    react: {
      version: 'detect',
    },
    'import/resolver': {
      node: {
        extensions: ['.js', '.jsx'],
      },
    },
  },
  rules: {
    // Overrides and custom rules:
    'react/prop-types': 'off',
    'react/react-in-jsx-scope': 'off',
    'react/jsx-filename-extension': [1, { extensions: ['.js', '.jsx'] }],
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
    'no-console': ['warn', { allow: ['warn', 'error'] }],
    // Add any specific rule overrides or custom rules here
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
