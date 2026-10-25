import type { IndexingRunResponse, SourceBlock } from '@/shared/api/types'
import type { StatusTone } from '@/shared/ui/StatusLine'

/** The fixed words of a library's state; the head, the run row and the run detail share them. */
export type LibraryStateWord =
  | 'Aktuell'
  | 'Läuft'
  | 'Teilweise fehlgeschlagen'
  | 'Fehlgeschlagen'
  | 'Anmeldung abgelehnt'
  | 'Zustimmung abgelaufen'
  | 'Kontingent voll'
  | 'Noch nie indiziert'
  | 'Noch leer'
  | 'Wird gelöscht'
  | 'Nachfolge offen'

/** Where the word leads: the place of the cause. */
export type StateTarget = 'verlauf' | 'verbindung' | 'dokumente' | 'freigaben' | null

export interface LibraryState {
  word: LibraryStateWord
  tone: StatusTone
  /** The one addition: a date or a percentage. */
  extra: string | null
  target: StateTarget
}

/** The category under which the source refused the sign-in. */
const REFUSED_CATEGORY = 'CREDENTIALS_REJECTED'

export function formatShortDate(value: string | null | undefined): string | null {
  if (!value) return null
  return new Date(value).toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

/** „24.10.2026, 06:00“ - start of a run in the run table. */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** Areas of the scope a run could not read, from either kind of report. */
export function unreadableCount(run: IndexingRunResponse): number {
  return Math.max(run.unreadableScopeCount ?? 0, run.unlistedScopeKeys?.length ?? 0)
}

/**
 * The word of one run. A completed run with failed documents, unreadable areas or an early stop
 * is never „Aktuell“; a refused sign-in or an exhausted quota is named as such.
 */
export function runState(run: IndexingRunResponse): { word: LibraryStateWord; tone: StatusTone } {
  if (run.status === 'RUNNING') return { word: 'Läuft', tone: 'neutral' }
  if (run.failureCategory === 'QUOTA_EXHAUSTED') return { word: 'Kontingent voll', tone: 'warning' }
  if (run.status === 'FAILED') {
    if (run.failureCategory === REFUSED_CATEGORY)
      return { word: 'Anmeldung abgelehnt', tone: 'error' }
    if (run.failureCategory === 'EXPIRED') return { word: 'Zustimmung abgelaufen', tone: 'error' }
    return { word: 'Fehlgeschlagen', tone: 'error' }
  }
  if (run.documentsFailed > 0 || unreadableCount(run) > 0 || run.incomplete) {
    return { word: 'Teilweise fehlgeschlagen', tone: 'warning' }
  }
  return { word: 'Aktuell', tone: 'success' }
}

/**
 * The word of a run in the run table: what the run achieved. „Aktuell“ describes the library,
 * not a past run, so a clean run is „Erfolgreich“ there; every other word is the same.
 */
export function runRowState(run: IndexingRunResponse): { word: string; tone: StatusTone } {
  const state = runState(run)
  return state.word === 'Aktuell' ? { word: 'Erfolgreich', tone: state.tone } : state
}

function targetOf(word: LibraryStateWord): StateTarget {
  switch (word) {
    case 'Anmeldung abgelehnt':
    case 'Zustimmung abgelaufen':
      return 'verbindung'
    case 'Nachfolge offen':
      return 'freigaben'
    case 'Wird gelöscht':
    case 'Noch leer':
      return null
    default:
      return 'verlauf'
  }
}

export interface ConnectorStateInput {
  erasureRequestedAt: string | null
  successionOpen: boolean
  sourceBlock: SourceBlock | null
  /** The newest run of the history, when the caller may read it. */
  newestRun: IndexingRunResponse | null
  /** The status endpoint's view, for readers without a run history and for the live progress. */
  status: {
    status: string
    timestamp: string | null
    documentsFailed: number
    unlistedScopeKeys: string[]
    failureCategory: string | null
    percent: number | null
  }
  lastScheduledRunsFailed: boolean
}

function state(word: LibraryStateWord, tone: StatusTone, extra: string | null): LibraryState {
  return { word, tone, extra, target: targetOf(word) }
}

/** The state of a connected library: one word from one source, in a fixed precedence. */
export function connectorState(input: ConnectorStateInput): LibraryState {
  const { status, newestRun } = input
  if (input.erasureRequestedAt) {
    return state('Wird gelöscht', 'error', formatShortDate(input.erasureRequestedAt))
  }
  if (input.successionOpen) return state('Nachfolge offen', 'warning', null)
  if (status.status === 'RUNNING') {
    return state('Läuft', 'neutral', status.percent != null ? `${status.percent} %` : null)
  }
  if (input.sourceBlock?.reason === 'EXPIRED') return state('Zustimmung abgelaufen', 'error', null)
  if (newestRun) {
    const { word, tone } = runState({
      ...newestRun,
      unlistedScopeKeys: newestRun.unlistedScopeKeys ?? status.unlistedScopeKeys,
    })
    return state(word, tone, formatShortDate(newestRun.completedAt ?? newestRun.startedAt))
  }
  if (status.status === 'IDLE') {
    return input.lastScheduledRunsFailed
      ? state('Fehlgeschlagen', 'error', null)
      : state('Noch nie indiziert', 'neutral', null)
  }
  const { word, tone } = runState({
    id: '',
    status: status.status as IndexingRunResponse['status'],
    triggeredBy: 'SCHEDULED',
    runMode: 'INCREMENTAL',
    documentCount: 0,
    totalDocuments: 0,
    documentsSkipped: 0,
    documentsFailed: status.documentsFailed,
    documentsIndexedTotal: 0,
    failureCategory: status.failureCategory,
    unlistedScopeKeys: status.unlistedScopeKeys,
    startedAt: status.timestamp ?? '',
    events: [],
    eventsTruncatedCount: 0,
  })
  return state(word, tone, formatShortDate(status.timestamp))
}

/**
 * The state of an upload library from what its documents say: processing, failed documents or
 * a full quota. Counts are left out - the list reports them per page only - and the date is
 * the last upload.
 */
export function uploadState(input: {
  erasureRequestedAt: string | null
  successionOpen: boolean
  quotaFull: boolean
  processing: boolean
  failed: boolean
  documentCount: number
  lastUpload: string | null
}): LibraryState {
  if (input.erasureRequestedAt) {
    return state('Wird gelöscht', 'error', formatShortDate(input.erasureRequestedAt))
  }
  if (input.successionOpen) return state('Nachfolge offen', 'warning', null)
  const date = formatShortDate(input.lastUpload)
  if (input.quotaFull) return { ...state('Kontingent voll', 'warning', date), target: 'dokumente' }
  if (input.documentCount === 0) return state('Noch leer', 'neutral', null)
  // a failure outweighs a running upload: it needs action, the upload ends by itself
  if (input.failed) {
    return { ...state('Teilweise fehlgeschlagen', 'warning', date), target: 'dokumente' }
  }
  // no counter of the upload's progress exists, so „Läuft“ carries no addition
  if (input.processing) return { ...state('Läuft', 'neutral', null), target: null }
  return { ...state('Aktuell', 'success', date), target: null }
}
