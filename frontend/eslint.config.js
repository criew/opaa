import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import eslintConfigPrettier from 'eslint-config-prettier'
import jsxA11yX from 'eslint-plugin-jsx-a11y-x'
import { defineConfig, globalIgnores } from 'eslint/config'
import themeBoundaries from './eslint/boundaries.js'
import { MOCK_GLOBS, TEST_INFRA } from './eslint/themes.js'

export default defineConfig([
  globalIgnores(['dist', 'dist-prototype', 'public/mockServiceWorker.js']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
      // Accessibility rules. eslint-plugin-jsx-a11y-x is the ESLint-10-compatible fork of
      // eslint-plugin-jsx-a11y; switching back to upstream is tracked in #635. Exceptions are
      // disabled per line with a reason and an issue reference, never globally.
      jsxA11yX.configs.recommended,
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
  },
  // Production files stay below 800 lines (counting every line); a warning, so lint still passes.
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: [
      'src/**/*.test.{ts,tsx}',
      `${TEST_INFRA}/**`,
      'src/shared/api/generated/**',
      ...MOCK_GLOBS,
    ],
    rules: {
      'max-lines': ['warn', { max: 800, skipBlankLines: false, skipComments: false }],
    },
  },
  // Theme boundaries; rules in frontend/AGENTS.md.
  themeBoundaries,
  eslintConfigPrettier,
])
