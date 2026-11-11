import { describe, expect, it } from 'vitest'

/** The application's source text, read in by Vite (`?raw`), as in `states/stateRules.test.ts`. */
const SOURCES = import.meta.glob('/src/**/*.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const PRODUCTION_FILES = Object.keys(SOURCES).filter(
  (path) => !path.includes('.test.') && !path.includes('/test/') && !path.includes('/mocks/'),
)

/** Every page before the session lives in the theme `auth`. */
const BEFORE_SIGN_IN = PRODUCTION_FILES.filter((path) => path.includes('/features/auth/'))

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

  // Accent stays with action and state; an uppercase label that names a section is grey.
  it('never colours an uppercase label in the accent', () => {
    const offenders = PRODUCTION_FILES.filter((path) => {
      const source = SOURCES[path]
      return [...source.matchAll(/textTransform: 'uppercase'/g)].some((match) =>
        /\bcolor: ['"`]primary\b/.test(enclosingObject(source, match.index)),
      )
    })
    expect(offenders).toEqual([])
  })
})

describe('pages before the sign-in (guidelines 5.1, 5.2 and 5.12)', () => {
  it('covers the pages', () => {
    expect(BEFORE_SIGN_IN.length).toBeGreaterThan(10)
  })

  it('builds every field from FormField or SecretField', () => {
    expect(
      matching(BEFORE_SIGN_IN, /@mui\/material\/(TextField|OutlinedInput|InputAdornment)'/),
    ).toEqual([])
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
