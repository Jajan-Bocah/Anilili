import js from '@eslint/js'
import globals from 'globals'
import react from 'eslint-plugin-react'
import reactHooks from 'eslint-plugin-react-hooks'
import jsxA11y from 'eslint-plugin-jsx-a11y'

export default [
  {
    ignores: ['dist/**', 'node_modules/**', 'coverage/**', '.wrangler/**', 'eslint.config.js'],
  },
  js.configs.recommended,
  {
    files: ['**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.es2022,
        JSX: true,
      },
      parserOptions: {
        ecmaFeatures: { jsx: true },
      },
    },
    settings: {
      react: { version: '18.3' },
    },
    plugins: {
      react,
      'react-hooks': reactHooks,
      'jsx-a11y': jsxA11y,
    },
    rules: {
      ...react.configs.recommended.rules,
      'react/react-in-jsx-scope': 'off',
      'react/prop-types': 'warn',
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      'jsx-a11y/anchor-is-valid': 'warn',
      'jsx-a11y/click-events-have-key-events': 'warn',
      'jsx-a11y/no-noninteractive-element-interactions': 'warn',
      'no-unused-vars': 'warn',
      'no-console': 'warn',
      'no-alert': 'error',
      'no-eval': 'error',
      'no-implicit-globals': 'error',
      'no-lone-blocks': 'warn',
      'no-multi-spaces': 'warn',
      'no-trailing-spaces': 'warn',
      'semi': ['error', 'never'],
      'quotes': ['error', 'single'],
      'indent': ['error', 2, { SwitchCase: 1 }],
      'eol-last': 'error',
      'comma-dangle': ['error', 'only-multiline'],
      'object-curly-spacing': ['error', 'always'],
      'array-bracket-spacing': ['error', 'never'],
      'space-before-blocks': 'error',
      'space-before-function-paren': ['error', 'never'],
      'keyword-spacing': 'error',
    },
  },
  {
    // Playwright specs run in Node and follow Playwright's own formatting
    files: ['tests/e2e/**/*.js', 'playwright.config.js'],
    languageOptions: {
      globals: { ...globals.node },
    },
    rules: {
      'space-before-function-paren': 'off',
      'no-unused-vars': 'warn',
      'no-console': 'off',
    },
  },
]
