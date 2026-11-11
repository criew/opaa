import { describe, expect, it } from 'vitest'

/**
 * The application's source text, read in by Vite (`?raw`) - no file system access, so that the
 * guards below get by without Node types.
 */
const QUELLEN = {
  ...(import.meta.glob('/src/**/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('/src/**/*.ts', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
}

const PRODUKTIVDATEIEN = Object.keys(QUELLEN).filter(
  (pfad) =>
    !pfad.includes('.test.') &&
    !pfad.includes('/test/') &&
    !pfad.includes('/mocks/') &&
    !pfad.includes('/generated/'),
)

/** The three building blocks themselves; they alone may carry what the rules forbid elsewhere. */
const BAUSTEINE = '/src/shared/ui/states/'

/** Sign-in and installation stand before any application frame; no rule of 5.7 applies there. */
const VOR_DER_ANWENDUNG = ['/features/auth/', '/features/installation/', '/app/ProtectedRoute.tsx']

/**
 * The one named exception inside the application, and only from the spinner rule: the status line
 * of the running chat answer shows a spinner with a short visible sentence about a process.
 */
const SPINNER_AUSNAHMEN = ['/features/chat/components/ChatStatusLine.tsx']

const imGeltungsbereich = (pfad: string) =>
  !pfad.includes(BAUSTEINE) && !VOR_DER_ANWENDUNG.some((ausnahme) => pfad.includes(ausnahme))

function treffer(muster: RegExp, filter: (pfad: string) => boolean = imGeltungsbereich) {
  return PRODUKTIVDATEIEN.filter((pfad) => filter(pfad) && muster.test(QUELLEN[pfad]))
}

describe('Leer-, Lade- und Fehlerzustände (guidelines 5.7)', () => {
  it('draws skeletons only in LoadingState', () => {
    expect(treffer(/@mui\/material\/Skeleton/, (pfad) => !pfad.includes(BAUSTEINE))).toEqual([])
  })

  it('shows a spinner only inside the button that started the action', () => {
    // A busy button uses MUI's `loading` prop; a spinner of its own is a loading state that
    // belongs to LoadingState instead.
    expect(
      treffer(
        /@mui\/material\/CircularProgress/,
        (pfad) =>
          imGeltungsbereich(pfad) && !SPINNER_AUSNAHMEN.some((ausnahme) => pfad.endsWith(ausnahme)),
      ),
    ).toEqual([])
  })

  it('shows a progress bar only where it measures progress', () => {
    const unbestimmt = PRODUKTIVDATEIEN.filter(
      (pfad) =>
        imGeltungsbereich(pfad) &&
        /<LinearProgress\b(?![^>]*variant="determinate")[^>]*>/.test(QUELLEN[pfad]),
    )
    expect(unbestimmt).toEqual([])
  })

  it('never shows „wird geladen" as text', () => {
    expect(treffer(/\b(?:wird|werden) geladen\b|Wird geladen|\bLädt\b|\bLade …/)).toEqual([])
  })

  it('offers „Erneut laden" only through LoadError and never „Neu laden"', () => {
    expect(treffer(/Erneut laden/)).toEqual([])
    expect(treffer(/Neu laden/, () => true)).toEqual([])
  })

  it('ends every empty text with a full stop', () => {
    // A literal empty text up to its closing quote or tag; dynamic continuations are left out.
    const leertext = /["'`>]\s*((?:Noch keine|Keine Treffer)[^"'`<{}]*)(["'`<])/g
    const ohnePunkt = PRODUKTIVDATEIEN.filter(imGeltungsbereich).flatMap((pfad) =>
      [...QUELLEN[pfad].matchAll(leertext)]
        .map((m) => m[1].trim())
        .filter((text) => !text.endsWith('.'))
        .map((text) => `${pfad}: ${text}`),
    )
    expect(ohnePunkt).toEqual([])
  })

  it('reports background work through notify, never a snackbar of its own', () => {
    expect(
      treffer(/@mui\/material\/Snackbar/, (pfad) => !pfad.endsWith('/NotificationHost.tsx')),
    ).toEqual([])
  })

  it('keeps no list-specific loading or empty placeholder beside the building blocks', () => {
    expect(treffer(/\bListLoading\b|\bListEmptyState\b/, () => true)).toEqual([])
  })
})
