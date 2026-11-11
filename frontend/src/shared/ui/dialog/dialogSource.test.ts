import { describe, expect, it } from 'vitest'

/*
 * Source guard for the footer of the form dialogs (guidelines 5.6). Read through Vite's `?raw`
 * import, so the guard gets by without Node types.
 */
const SOURCES = import.meta.glob('../../../**/*.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

// features and app only: shared/ui is where the building blocks themselves live
const PRODUCTION = Object.entries(SOURCES).filter(
  ([path]) =>
    /^\.\.\/\.\.\/\.\.\/(features|app)\//.test(path) &&
    !path.includes('.test.') &&
    !path.includes('/mocks/'),
)

/** Labels of more than one word that are the action's own name, not a verb with its object. */
const ALLOWED_PHRASES = ['Trotzdem zuordnen', 'Weiter zum Anbieter']

function submitLabels(source: string): string[] {
  const labels: string[] = []
  for (const match of source.matchAll(/submitLabel=(?:"([^"]*)"|\{([^}]*)\})/g)) {
    if (match[1] !== undefined) labels.push(match[1])
    // the branches of a condition, not the values it compares with
    else for (const literal of match[2].matchAll(/[?:]\s*'([^']*)'/g)) labels.push(literal[1])
  }
  return labels
}

describe('form dialogs in the source', () => {
  const dialogs = PRODUCTION.filter(([, source]) => source.includes('<FormDialog'))

  it('finds the form dialogs of the application', () => {
    expect(dialogs.length).toBeGreaterThan(20)
  })

  it.each(dialogs)('%s names its action by the verb alone', (_path, source) => {
    for (const label of submitLabels(source)) {
      if (ALLOWED_PHRASES.includes(label)) continue
      expect(label, 'a verb alone, e.g. „Anlegen“ or „Speichern“').toMatch(/^[A-ZÄÖÜ][a-zäöüß]+$/)
    }
  })

  it.each(PRODUCTION)('%s builds no dialog footer of its own', (_path, source) => {
    expect(source).not.toMatch(/<DialogActions|<DialogTitle[\s>]/)
  })
})
