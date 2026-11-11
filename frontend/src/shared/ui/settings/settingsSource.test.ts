import { describe, expect, it } from 'vitest'

/*
 * Source guards for saving, switches and the connection test (guidelines 5.16). Read through
 * Vite's `?raw` import, so the guards get by without Node types.
 */
const SOURCES = import.meta.glob('/src/**/*.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const PRODUCTION = Object.entries(SOURCES).filter(
  ([path]) => !path.includes('.test.') && !path.includes('/test/') && !path.includes('/mocks/'),
)

/** JSX text and string literals of a file: where a label can stand. */
function texts(source: string): string[] {
  const found: string[] = []
  for (const match of source.matchAll(/>\s*([^<>{}]+?)\s*</g)) found.push(match[1])
  for (const match of source.matchAll(/'([^'\n]*)'|"([^"\n]*)"|`([^`]*)`/g)) {
    found.push(match[1] ?? match[2] ?? match[3])
  }
  return found.map((text) => text.replace(/\s+/g, ' ').trim())
}

/** „Regeln speichern“, „Zugang speichern“: a label of a few words that ends in the verb. */
const SAVE_WITH_OBJECT = /^\S+( \S+){0,3} speichern$/i

/** The opening tag of the button whose label is exactly „Speichern“. */
function saveButtonTags(source: string): string[] {
  const tags: string[] = []
  for (const match of source.matchAll(/>\s*Speichern\s*<\/(?:Busy)?Button>/g)) {
    const before = source.slice(0, match.index)
    const start = Math.max(before.lastIndexOf('<Button'), before.lastIndexOf('<BusyButton'))
    tags.push(source.slice(start, match.index))
  }
  return tags
}

/** A condition on the entries rather than on the running request. */
const GATED_ON_INPUT = /disabled=\{[^}]*(changed|dirty|valid|canSave|===|!==|\.trim\(\))/i

/** Where „Verbindung testen“ is no button of a form: the wizard step and a row menu entry. */
const OTHER_CONNECTION_TESTS = [
  '/src/features/library/components/setup/ConnectionCheckView.tsx',
  '/src/features/model/components/ModelRowMenu.tsx',
]

describe('saving, switches and the connection test in the source (guidelines 5.16)', () => {
  it('finds the settings forms of the application', () => {
    const forms = PRODUCTION.filter(([, source]) => source.includes('<SettingsForm'))
    expect(forms.length).toBeGreaterThanOrEqual(10)
  })

  it.each(PRODUCTION)('%s labels saving by the verb alone', (_path, source) => {
    expect(texts(source).filter((text) => SAVE_WITH_OBJECT.test(text))).toEqual([])
  })

  it.each(PRODUCTION)('%s keeps „Speichern“ active whatever is entered', (_path, source) => {
    expect(saveButtonTags(source).filter((tag) => GATED_ON_INPUT.test(tag))).toEqual([])
  })

  it.each(PRODUCTION.filter(([, source]) => source.includes('<SettingsForm')))(
    '%s has no switch inside its settings form',
    (_path, source) => {
      for (const form of source.matchAll(/<SettingsForm[\s\S]*?<\/SettingsForm>/g)) {
        expect(form[0]).not.toMatch(/<Switch[\s/>]/)
      }
    },
  )

  it('draws „Verbindung testen“ only in ConnectionTestButton', () => {
    const drawn = PRODUCTION.filter(
      ([path, source]) =>
        !path.endsWith('/shared/ui/settings/ConnectionTestButton.tsx') &&
        !OTHER_CONNECTION_TESTS.includes(path) &&
        texts(source).some((text) => /^Verbindung (testen|prüfen)$/.test(text)),
    ).map(([path]) => path)
    expect(drawn).toEqual([])
  })
})
