module.exports = {
  env: {
    browser: true,
    es2021: true,
    node: true,
  },
  extends: [
    'eslint:recommended',
    'plugin:react/recommended',
    'plugin:react-hooks/recommended',
  ],
  parserOptions: {
    ecmaFeatures: {
      jsx: true,
    },
    ecmaVersion: 'latest',
    sourceType: 'module',
  },
  plugins: ['react', 'react-hooks'],
  rules: {
    // Hook rules
    'react-hooks/rules-of-hooks': 'error',
    'react-hooks/exhaustive-deps': 'warn',

    // Component structure rules
    'react/jsx-max-depth': ['warn', { max: 4 }],
    'react/jsx-max-props-per-line': ['warn', { maximum: 3, when: 'multiline' }],
    'react/jsx-sort-props': 'warn',
    'react/no-multi-comp': 'warn',

    // General code style rules
    'max-lines': ['warn', { max: 150, skipBlankLines: true }],
    semi: ['error', 'always'],
    quotes: ['warn', 'single'],
    indent: ['warn', 2],
    'comma-dangle': ['warn', 'only-multiline'],
  },
  settings: {
    react: {
      version: 'detect',
    },
  },
};
