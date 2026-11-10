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

const NO_SIGNAL_CHIP = {
  selector:
    "JSXOpeningElement[name.name='Chip'] > JSXAttribute[name.name='color'] Literal[value=/^(warning|error|success)$/]",
  message:
    'No chip in a signal colour (guidelines 5.5): a state is a StateDot, a label stays neutral.',
}

const LOCALE_FORMAT_MESSAGE =
  'Dates, moments and numbers come from @/shared/utils/formatDate (guidelines 6).'
const NO_LOCALE_FORMAT = [
  {
    selector: 'CallExpression[callee.property.name=/^toLocale(Date|Time)?String$/]',
    message: LOCALE_FORMAT_MESSAGE,
  },
  {
    selector: "NewExpression[callee.object.name='Intl'][callee.property.name='DateTimeFormat']",
    message: LOCALE_FORMAT_MESSAGE,
  },
]

const FONT_SIZE_MESSAGE =
  'Font sizes are type steps: fontSize.label|meta|text|title|detailTitle|wizardTitle, icons iconSize.* (guidelines 3.2).'
// The value of `fontSize`, also as either branch of a condition or a breakpoint object.
const FONT_SIZE_VALUE = [
  "Property[key.name='fontSize'] > Literal",
  "Property[key.name='fontSize'] > ConditionalExpression > Literal",
  "Property[key.name='fontSize'] > ObjectExpression > Property > Literal",
  "JSXAttribute[name.name='fontSize'] > JSXExpressionContainer > Literal",
]
const NO_FONT_SIZE_NUMBER = FONT_SIZE_VALUE.flatMap((value) => [
  { selector: `${value}[raw=/^[0-9.]+$/]`, message: FONT_SIZE_MESSAGE },
  { selector: `${value}[value=/^[0-9.]+(px|rem)$/]`, message: FONT_SIZE_MESSAGE },
])

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
  // Guidelines 5.5: no chip in a signal colour - a state is a dot before its word (StateDot).
  {
    files: ['src/**/*.tsx'],
    rules: { 'no-restricted-syntax': ['error', NO_SIGNAL_CHIP] },
  },
  // Guidelines 6: dates and moments come from shared/utils/formatDate, numbers from formatNumber.
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/**/*.test.{ts,tsx}', 'src/shared/utils/formatDate.ts'],
    rules: { 'no-restricted-syntax': ['error', NO_SIGNAL_CHIP, ...NO_LOCALE_FORMAT] },
  },
  // Guidelines 3.2: features and app name a type step (fontSize.*) or icon size, never a number.
  {
    files: ['src/features/**/*.{ts,tsx}', 'src/app/**/*.{ts,tsx}'],
    ignores: ['src/**/*.test.{ts,tsx}'],
    rules: {
      'no-restricted-syntax': [
        'error',
        NO_SIGNAL_CHIP,
        ...NO_LOCALE_FORMAT,
        ...NO_FONT_SIZE_NUMBER,
      ],
    },
  },
  // Theme boundaries; rules in frontend/AGENTS.md.
  themeBoundaries,
  eslintConfigPrettier,
])
