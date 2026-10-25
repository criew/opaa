import { describe, expect, it } from 'vitest'
import type { IndexingRunResponse, SourceBlock } from '@/shared/api/types'
import { libraryState, runWord, stateTargetHref } from './libraryState'

function run(overrides: Partial<IndexingRunResponse> = {}): IndexingRunResponse {
  return {
    id: 'run-1',
    status: 'COMPLETED',
    triggeredBy: 'SCHEDULED',
    runMode: 'INCREMENTAL',
    documentCount: 12,
    totalDocuments: 12,
    documentsSkipped: 0,
    documentsFailed: 0,
    documentsIndexedTotal: 12,
    startedAt: '2026-10-23T06:00:00Z',
    completedAt: '2026-10-23T06:07:00Z',
    events: [],
    eventsTruncatedCount: 0,
    ...overrides,
  }
}

function block(reason: SourceBlock['reason']): SourceBlock {
  return { reason, notice: 'Gesperrt', responsible: 'die Systemverwaltung' } as SourceBlock
}

const base = {
  library: { sourceType: 'NEXTCLOUD' as const, updatedAt: '2026-10-20T10:00:00Z' },
  documentCount: 12,
  erasureRequestedAt: null,
  succession: null,
  newestRun: null,
  runningPercent: null,
  signsIn: true,
  canSeeSource: true,
  uploadQuotaRejected: false,
}

describe('libraryState', () => {
  it('names a marked erasure before anything else, with its day and without a link', () => {
    const state = libraryState({
      ...base,
      erasureRequestedAt: '2026-10-24T08:00:00Z',
      succession: { addresseeLabel: 'Systemverwaltung' } as never,
      newestRun: run({ status: 'FAILED' }),
    })
    expect(state).toEqual({
      word: 'Wird gelöscht',
      tone: 'error',
      detail: '24.10.2026',
      target: null,
    })
  })

  it('names an open succession next and leads to the shares', () => {
    const state = libraryState({
      ...base,
      succession: { addresseeLabel: 'Systemverwaltung' } as never,
      newestRun: run(),
    })
    expect(state.word).toBe('Nachfolge offen')
    expect(state.target).toEqual({ tab: 'freigaben' })
  })

  it('says „Kontingent voll“ for an upload library at its quota or after a refused upload', () => {
    const upload = { sourceType: 'UPLOAD' as const, updatedAt: '2026-10-20T10:00:00Z' }
    expect(
      libraryState({
        ...base,
        library: { ...upload, storageQuotaBytes: 100, storageUsedBytes: 100 },
      }).word,
    ).toBe('Kontingent voll')
    expect(libraryState({ ...base, library: upload, uploadQuotaRejected: true }).word).toBe(
      'Kontingent voll',
    )
    expect(libraryState({ ...base, library: upload, documentCount: 0 }).word).toBe(
      'Noch kein Inhalt',
    )
    expect(libraryState({ ...base, library: upload })).toMatchObject({
      word: 'Hochgeladen',
      detail: '20.10.2026',
    })
  })

  it('names the failed uploads with their number and leads to them, for every role', () => {
    const upload = { sourceType: 'UPLOAD' as const, updatedAt: '2026-10-20T10:00:00Z' }
    const failed = libraryState({
      ...base,
      library: upload,
      failedDocuments: 1234,
      canSeeSource: false,
    })
    // the number stands alone: documentCount counts top-level documents, the failures count rows
    expect(failed).toEqual({
      word: 'Teilweise fehlgeschlagen',
      tone: 'warning',
      detail: '1.234 Fehler',
      target: { tab: 'dokumente', status: 'FAILED' },
    })
    expect(stateTargetHref(failed.target!)).toBe('?status=FAILED')
    // a full quota still wins; no failure, or none known yet, leaves „Hochgeladen“
    expect(
      libraryState({ ...base, library: upload, failedDocuments: 2, uploadQuotaRejected: true })
        .word,
    ).toBe('Kontingent voll')
    expect(libraryState({ ...base, library: upload, failedDocuments: 0 }).word).toBe('Hochgeladen')
    expect(libraryState({ ...base, library: upload, failedDocuments: null }).word).toBe(
      'Hochgeladen',
    )
  })

  it('leaves a connector library to its runs, whatever its failed documents', () => {
    expect(libraryState({ ...base, failedDocuments: 3 }).word).toBe('Noch nie indiziert')
  })

  it('says „Läuft“ with the percent, or with an unknown state when polling gave up', () => {
    expect(libraryState({ ...base, runningPercent: 40 })).toMatchObject({
      word: 'Läuft',
      detail: '40 %',
    })
    expect(libraryState({ ...base, runningPercent: null, runningUnknown: true })).toMatchObject({
      word: 'Läuft',
      detail: 'Stand unbekannt',
    })
  })

  it.each([
    ['TYPE_LOCKED', 'Gesperrt'],
    ['PROFILE_LOCKED', 'Gesperrt'],
    ['PROFILE_REQUIRED', 'Gesperrt'],
    ['ACCESS_REMOVED', 'Zugang entfernt'],
    ['TARGET_OUTSIDE_PROFILE', 'Adresse außerhalb des Zugangs'],
    ['EXPIRED', 'Anmeldung abgelaufen'],
    ['NOT_CONNECTED', 'Verbindung getrennt'],
    ['OWNER_DEACTIVATED', 'Konto deaktiviert'],
  ] as const)(
    'names a source blocked with %s as „%s“ and leads to the connection',
    (reason, word) => {
      // the newest run completed: the block, not the run, says why nothing is updated any more
      const state = libraryState({
        ...base,
        library: { ...base.library, sourceBlock: block(reason) },
        newestRun: run(),
      })
      expect(state).toEqual({
        word,
        tone: 'error',
        detail: null,
        target: { tab: 'quelle', anchor: 'verbindung' },
      })
    },
  )

  it('names a resting account as a warning only', () => {
    const state = libraryState({
      ...base,
      library: { ...base.library, sourceBlock: block('DORMANT') },
      newestRun: run(),
    })
    expect(state).toMatchObject({ word: 'Ruhend', tone: 'warning' })
  })

  it('names a removed profile as „Zugang entfernt“', () => {
    const state = libraryState({ ...base, connectionProfileRemoved: true, newestRun: run() })
    expect(state).toMatchObject({ word: 'Zugang entfernt', target: { anchor: 'verbindung' } })
  })

  it('reads the newest run: „Aktuell“ with its day, a refused sign-in at the connection', () => {
    expect(libraryState({ ...base, newestRun: run() })).toEqual({
      word: 'Aktuell',
      tone: 'success',
      detail: '23.10.2026',
      target: { tab: 'quelle', anchor: 'laeufe' },
    })
    expect(
      libraryState({
        ...base,
        newestRun: run({ status: 'FAILED', failureCategory: 'CREDENTIALS_REJECTED' }),
      }),
    ).toMatchObject({ word: 'Anmeldung abgelehnt', target: { anchor: 'verbindung' } })
    expect(libraryState({ ...base, newestRun: run({ documentsFailed: 2 }) })).toMatchObject({
      word: 'Teilweise fehlgeschlagen',
      tone: 'warning',
    })
  })

  // regression guard: a caller below MANAGER may not read the run history and saw
  // „Noch nie indiziert“ for every indexed library
  it('reads the last run a caller below MANAGER knows when there is no history', () => {
    expect(
      libraryState({
        ...base,
        canSeeSource: false,
        lastRun: { status: 'COMPLETED', at: '2026-10-22T06:07:00Z' },
      }),
    ).toEqual({ word: 'Aktuell', tone: 'success', detail: '22.10.2026', target: null })
    expect(
      libraryState({ ...base, canSeeSource: false, lastRun: { status: 'FAILED', at: null } }),
    ).toMatchObject({ word: 'Fehlgeschlagen', tone: 'error', target: null })
    expect(libraryState({ ...base, canSeeSource: false, lastRun: null }).word).toBe(
      'Noch nie indiziert',
    )
  })

  it('keeps the word but drops the link into the source for a reader', () => {
    const state = libraryState({
      ...base,
      canSeeSource: false,
      library: { ...base.library, sourceBlock: block('TYPE_LOCKED') },
    })
    expect(state).toMatchObject({ word: 'Gesperrt', target: null })
  })
})

describe('runWord', () => {
  it('never says „Anmeldung“ for a source without a sign-in', () => {
    const refused = run({ status: 'FAILED', failureCategory: 'CREDENTIALS_REJECTED' })
    expect(runWord(refused, true)).toBe('Anmeldung abgelehnt')
    expect(runWord(refused, false)).toBe('Nicht erreichbar')
    expect(runWord(run({ status: 'FAILED' }), false)).toBe('Nicht erreichbar')
    expect(runWord(run({ status: 'FAILED' }), true)).toBe('Fehlgeschlagen')
  })

  it('counts a refused or unreachable part as partly failed (ADR-0023)', () => {
    const refusedPart = run({
      events: [{ category: 'REJECTED', reference: 'Space A', message: 'abgewiesen' } as never],
    })
    expect(runWord(refusedPart)).toBe('Teilweise fehlgeschlagen')
    expect(runWord(run({ status: 'FAILED', failureCategory: 'QUOTA_EXHAUSTED' }))).toBe(
      'Kontingent voll',
    )
  })
})

describe('stateTargetHref', () => {
  it('leads to a row of the Reiter „Quelle“ and opens the newest run for „Läufe“', () => {
    expect(stateTargetHref({ tab: 'quelle', anchor: 'verbindung' })).toBe('?tab=quelle#verbindung')
    expect(stateTargetHref({ tab: 'quelle', anchor: 'laeufe' })).toBe('?tab=quelle&lauf=neu#laeufe')
    expect(stateTargetHref({ tab: 'dokumente' })).toBe('')
  })
})
