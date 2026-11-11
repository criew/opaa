import { describe, expect, it } from 'vitest'

/** The application's source text, read in by Vite (`?raw`), as in `states/stateRules.test.ts`. */
const SOURCES = import.meta.glob('/src/**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const PRODUCTION_FILES = Object.keys(SOURCES).filter(
  (path) => !path.includes('.test.') && !path.includes('/test/') && !path.includes('/mocks/'),
)

/** Every page before the session lives in the theme `auth`. */
const BEFORE_SIGN_IN = PRODUCTION_FILES.filter((path) => path.includes('/features/auth/'))

/** `color` set to the accent in any spelling: a palette path, a theme callback, a token. */
const ACCENT_COLOR = /\bcolor:\s*[^,}]*(?<!text\.)\b(primary|accent)\b/

/** A raw input from MUI, by its own path or by name from the package root. */
const RAW_INPUT_IMPORT =
  /@mui\/material\/(TextField|OutlinedInput|InputAdornment)'|import\s*(?:type\s*)?\{[^}]*\b(TextField|OutlinedInput|InputAdornment)\b[^}]*\}\s*from\s*'@mui\/material'/

function matching(files: string[], pattern: RegExp): string[] {
  return files.filter((path) => pattern.test(SOURCES[path]))
}

/** The object literal around `index`, from its opening to its closing brace. */
function enclosingObject(source: string, index: number): string {
  let depth = 0
  let start = index
  while (start > 0) {
    start--
    if (source[start] === '}') depth++
    else if (source[start] === '{') {
      if (depth === 0) break
      depth--
    }
  }
  depth = 0
  let end = index
  while (end < source.length) {
    if (source[end] === '{') depth++
    else if (source[end] === '}') {
      if (depth === 0) break
      depth--
    }
    end++
  }
  return source.slice(start, end + 1)
}

describe('section heads (guidelines 2.3 and 3.3)', () => {
  it('finds the object literal around a position', () => {
    const source = "const a = { x: { color: 'primary.main' }, textTransform: 'uppercase', y: {} }"
    expect(enclosingObject(source, source.indexOf('textTransform'))).toBe(source.slice(10))
  })

  it.each([
    "color: 'primary.main'",
    'color: "primary.dark"',
    'color: (theme) => theme.palette.primary.main',
    'color: theme.vars.palette.primary.main',
    'color: accent[600]',
    "color: active ? 'primary.main' : 'text.secondary'",
  ])('recognises the accent in %s', (declaration) => {
    expect(ACCENT_COLOR.test(`{ ${declaration}, textTransform: 'uppercase' }`)).toBe(true)
  })

  it.each([
    "color: 'text.primary'",
    "color: 'text.secondary'",
    "borderColor: 'primary.main'",
    "bgcolor: 'primary.main'",
    "color: 'text.secondary', '&:hover': { borderColor: 'primary.main' }",
  ])('leaves %s alone', (declaration) => {
    expect(ACCENT_COLOR.test(`{ ${declaration}, textTransform: 'uppercase' }`)).toBe(false)
  })

  // Accent stays with action and state; an uppercase label that names a section is grey.
  it('never colours an uppercase label in the accent', () => {
    const offenders = PRODUCTION_FILES.filter((path) => {
      const source = SOURCES[path]
      return [...source.matchAll(/textTransform: 'uppercase'/g)].some((match) =>
        ACCENT_COLOR.test(enclosingObject(source, match.index)),
      )
    })
    expect(offenders).toEqual([])
  })
})

describe('pages before the sign-in (guidelines 5.1, 5.2 and 5.12)', () => {
  it('covers the pages', () => {
    expect(BEFORE_SIGN_IN.length).toBeGreaterThan(10)
  })

  it.each([
    "import TextField from '@mui/material/TextField'",
    "import { TextField } from '@mui/material'",
    "import { Box, OutlinedInput, Stack } from '@mui/material'",
    "import {\n  Box,\n  InputAdornment,\n} from '@mui/material'",
  ])('recognises the raw input in %s', (statement) => {
    expect(RAW_INPUT_IMPORT.test(statement)).toBe(true)
  })

  it('takes no other import for a raw input', () => {
    expect(RAW_INPUT_IMPORT.test("import { Box, Stack } from '@mui/material'")).toBe(false)
    expect(RAW_INPUT_IMPORT.test("import FormField from '@/shared/ui/form/FormField'")).toBe(false)
  })

  it('builds every field from FormField or SecretField', () => {
    expect(matching(BEFORE_SIGN_IN, RAW_INPUT_IMPORT)).toEqual([])
    expect(matching(BEFORE_SIGN_IN, /FormFieldLabel/)).toEqual([])
  })

  it('names sections with SectionHead', () => {
    expect(matching(BEFORE_SIGN_IN, /textTransform: 'uppercase'/)).toEqual([])
  })

  // A free-standing link is a TextLink; only the link inside a sentence stays a plain Link.
  it('routes free-standing links through TextLink', () => {
    expect(matching(BEFORE_SIGN_IN, /\bRouterLink\b/)).toEqual([
      '/src/features/auth/components/LocalSignInForm.tsx',
    ])
  })

  it('knows one wording for the way back to the sign-in', () => {
    expect(
      matching(BEFORE_SIGN_IN, /(?:Zurück|Zur regulären|Konto\? Zur) Anmeldung|Zurück zum Login/),
    ).toEqual([])
  })

  // The two buttons are the one action after a flow that ended without a form.
  it('writes the way back in SignInLink alone', () => {
    expect(matching(BEFORE_SIGN_IN, /(?:>\s*|')Zur Anmeldung(?:\s*<|')/).sort()).toEqual([
      '/src/features/auth/components/SignInLink.tsx',
      '/src/features/auth/pages/AuthCallbackPage.tsx',
      '/src/features/auth/pages/HandoverPage.tsx',
    ])
  })

  it.each([
    ['components/LocalSignInForm.tsx', 'Anmelden'],
    ['pages/RegisterPage.tsx', 'Registrieren'],
    ['pages/ForgotPasswordPage.tsx', 'Link anfordern'],
    ['pages/SetPasswordPage.tsx', 'Passwort festlegen'],
  ])('labels the primary button of %s with the verb of the page', (file, verb) => {
    const source = SOURCES[`/src/features/auth/${file}`]
    const idleLabels = [...source.matchAll(/\? '[^']+ …' : '([^']+)'\}/g)].map((match) => match[1])
    expect(idleLabels).toEqual([verb])
  })
})
