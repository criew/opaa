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
  'Dates, moments and numbers come from @/shared/utils/formatDate (formatDate, formatDateTime, formatNumber; guidelines 6).'
const NO_LOCALE_FORMAT = [
  {
    selector: 'CallExpression[callee.property.name=/^toLocale(Date|Time)?String$/]',
    message: LOCALE_FORMAT_MESSAGE,
  },
  {
    selector: 'CallExpression[callee.property.value=/^toLocale(Date|Time)?String$/]',
    message: LOCALE_FORMAT_MESSAGE,
  },
  {
    selector:
      ":matches(NewExpression, CallExpression)[callee.object.name='Intl'][callee.property.name=/^(DateTimeFormat|NumberFormat|RelativeTimeFormat)$/]",
    message: LOCALE_FORMAT_MESSAGE,
  },
]

const FONT_SIZE_MESSAGE =
  'Font sizes are type steps: fontSize.label|meta|text|title|detailTitle|wizardTitle, icons iconSize.* (guidelines 3.2).'
// The value of `fontSize`, also as either branch of a condition or a breakpoint object.
const FONT_SIZE_KEY = "Property:matches([key.name='fontSize'], [key.value='fontSize'])"
const FONT_SIZE_VALUE = [
  `${FONT_SIZE_KEY} > Literal`,
  `${FONT_SIZE_KEY} > ConditionalExpression > Literal`,
  `${FONT_SIZE_KEY} > ObjectExpression > Property > Literal`,
  `${FONT_SIZE_KEY} > ArrayExpression > Literal`,
  "JSXAttribute[name.name='fontSize'] > JSXExpressionContainer > Literal",
]
const NO_FONT_SIZE_NUMBER = [
  ...FONT_SIZE_VALUE.flatMap((value) => [
    { selector: `${value}[raw=/^[0-9.]+$/]`, message: FONT_SIZE_MESSAGE },
    { selector: `${value}[value=/^[0-9.]+(px|rem)$/]`, message: FONT_SIZE_MESSAGE },
  ]),
  { selector: `${FONT_SIZE_KEY} > TemplateLiteral`, message: FONT_SIZE_MESSAGE },
]

// Guidelines 5.1: a compact button never stands in a dialog's footer.
const NO_COMPACT_DIALOG_ACTION = {
  selector:
    "JSXElement[openingElement.name.name='DialogActions'] JSXOpeningElement[name.name=/Button$/] > JSXAttribute[name.name='size'][value.value='small']",
  message: 'Buttons in a dialog footer have the regular size (guidelines 5.1).',
}

// Guidelines 5.6: a dialog or side panel is one of the building blocks in shared/ui/dialog.
const DIALOG_MESSAGE =
  'Dialogs and side panels come from @/shared/ui/dialog (FormDialog, ViewDialog, SecretDialog, SidePanel), confirmations from confirmAction (guidelines 5.6, 5.10).'
const NO_RAW_DIALOG = {
  paths: [
    {
      name: '@mui/material',
      importNames: [
        'Dialog',
        'DialogActions',
        'DialogContent',
        'DialogContentText',
        'DialogTitle',
        'Drawer',
        'SwipeableDrawer',
      ],
      message: DIALOG_MESSAGE,
    },
  ],
  patterns: [
    {
      group: ['@mui/material/Dialog*', '@mui/material/Drawer', '@mui/material/SwipeableDrawer'],
      message: DIALOG_MESSAGE,
    },
  ],
}

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
  // Guidelines 5.1: no compact button in a dialog footer; listed in every block below, because a
  // later block replaces the rule's list for its files.
  {
    files: ['src/**/*.tsx'],
    rules: { 'no-restricted-syntax': ['error', NO_SIGNAL_CHIP, NO_COMPACT_DIALOG_ACTION] },
  },
  // Guidelines 6: dates and moments come from shared/utils/formatDate, numbers from formatNumber.
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/**/*.test.{ts,tsx}', 'src/shared/utils/formatDate.ts'],
    rules: {
      'no-restricted-syntax': [
        'error',
        NO_SIGNAL_CHIP,
        NO_COMPACT_DIALOG_ACTION,
        ...NO_LOCALE_FORMAT,
      ],
    },
  },
  // Guidelines 3.2: features and app name a type step (fontSize.*) or icon size, never a number.
  {
    files: ['src/features/**/*.{ts,tsx}', 'src/app/**/*.{ts,tsx}'],
    ignores: ['src/**/*.test.{ts,tsx}'],
    rules: {
      'no-restricted-syntax': [
        'error',
        NO_SIGNAL_CHIP,
        NO_COMPACT_DIALOG_ACTION,
        ...NO_LOCALE_FORMAT,
        ...NO_FONT_SIZE_NUMBER,
      ],
    },
  },
  // Guidelines 5.6: no dialog or drawer of its own outside shared/ui. Two files keep a drawer that
  // is no dialog of the admin area: the navigation of the shell on narrow screens, and the
  // evidence panel of the chat, the pattern for a view in a side panel (reworked with #3743).
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: [
      'src/shared/ui/**',
      'src/app/shell/AppShell.tsx',
      'src/features/chat/components/SourceEvidenceDrawer.tsx',
    ],
    rules: { 'no-restricted-imports': ['error', NO_RAW_DIALOG] },
  },
  // Theme boundaries; rules in frontend/AGENTS.md.
  themeBoundaries,
  eslintConfigPrettier,
])
