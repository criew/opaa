import type {
  IndexingRunResponse,
  LibraryResponse,
  SuccessionStateResponse,
} from '@/shared/api/types'
import type { StatusTone } from '@/shared/ui/StatusLine'
import type { LibraryDetailTab } from './libraryDetailAccess'

/** Where the cause of a state lives: a tab and, inside it, the anchor of its row. */
export interface StateTarget {
  tab: LibraryDetailTab
  anchor?: 'verbindung' | 'umfang' | 'zeitplan' | 'laeufe'
  /** Extra search parameters of the target, e.g. the documents' status filter. */
  params?: Record<string, string>
}

/**
 * The one state of a library: a word from the fixed list, one addition (date, percent or count)
 * and the place of its cause. The head, the run row and the run detail all read it from here.
 */
export interface LibraryState {
  word: string
  tone: StatusTone
  /** Date, percent or count; null when the word says enough. */
  detail: string | null
  target: StateTarget | null
}

/** The words of a run, the same as the head uses for the newest one. */
export type RunWord =
  | 'Abgeschlossen'
  | 'Teilweise fehlgeschlagen'
  | 'Fehlgeschlagen'
  | 'Läuft'
  | 'Anmeldung abgelehnt'
  | 'Nicht erreichbar'
  | 'Zustimmung abgelaufen'
  | 'Kontingent voll'

export function formatDay(value: string | null | undefined): string | null {
  if (!value) return null
  return new Date(value).toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

/** Events that are errors; removals, throttling and summaries are notes and count as nothing. */
const ERROR_CATEGORIES = new Set<IndexingRunResponse['events'][number]['category']>([
  'REJECTED',
  'UNREACHABLE',
  'UNSUPPORTED_FORMAT',
  'ALLOWLIST',
  'ERROR',
  'FORMAT_MISMATCH',
  'BUDGET_EXHAUSTED',
])

export function isErrorEvent(event: IndexingRunResponse['events'][number]): boolean {
  return ERROR_CATEGORIES.has(event.category)
}

/** A completed run that still failed on documents or parts of its scope. */
export function runPartlyFailed(run: IndexingRunResponse): boolean {
  return (
    run.status === 'COMPLETED' &&
    (run.documentsFailed > 0 || (run.unreadableScopeCount ?? 0) > 0 || Boolean(run.incomplete))
  )
}

/**
 * The word of one run; the head shows the same word for the newest run. A source without a
 * sign-in (directory, web, feed) never says „Anmeldung“: its refused run was not reachable.
 */
export function runWord(run: IndexingRunResponse, signsIn = true): RunWord {
  if (run.status === 'RUNNING') return 'Läuft'
  if (run.failureCategory === 'CREDENTIALS_REJECTED')
    return signsIn ? 'Anmeldung abgelehnt' : 'Nicht erreichbar'
  if (run.status === 'FAILED' && !run.failureCategory && !signsIn) return 'Nicht erreichbar'
  if (run.failureCategory === 'EXPIRED' || run.failureCategory === 'NOT_CONNECTED')
    return 'Zustimmung abgelaufen'
  if (run.failureCategory === 'QUOTA_EXHAUSTED') return 'Kontingent voll'
  if (run.status === 'FAILED') return 'Fehlgeschlagen'
  return runPartlyFailed(run) ? 'Teilweise fehlgeschlagen' : 'Abgeschlossen'
}

export function runTone(word: RunWord): StatusTone {
  switch (word) {
    case 'Abgeschlossen':
      return 'success'
    case 'Läuft':
      return 'neutral'
    case 'Teilweise fehlgeschlagen':
      return 'warning'
    default:
      return 'error'
  }
}

interface StateInput {
  library: Pick<LibraryResponse, 'sourceType' | 'updatedAt'> &
    Partial<Pick<LibraryResponse, 'storageQuotaBytes' | 'storageUsedBytes' | 'sourceBlock'>>
  documentCount: number
  erasureRequestedAt: string | null
  succession: SuccessionStateResponse | null | undefined
  /** The newest run of the history; null while there is none or it is not loaded. */
  newestRun: IndexingRunResponse | null
  /** The polled state of a running run: its percent, null while nothing runs. */
  runningPercent: number | null
  /** Upload: documents that failed to process. */
  failedDocuments: number
  /** Upload: documents still being processed. */
  pendingDocuments: number
  /** Whether the source signs in at all (no „Anmeldung“ for a directory, web or feed). */
  signsIn: boolean
  /** Whether the caller sees the source's rows and runs; a reader gets the word without a link. */
  canSeeSource: boolean
  /** Upload: the last upload was turned away at the quota. */
  uploadQuotaRejected: boolean
}

const VERBINDUNG: StateTarget = { tab: 'quelle', anchor: 'verbindung' }
const LAEUFE: StateTarget = { tab: 'quelle', anchor: 'laeufe' }

/** The one state of a library from what the page knows of it. */
export function libraryState(input: StateInput): LibraryState {
  const state = stateOf(input)
  // a reader does not see the rows and runs the cause lives in: the word stays, the link goes
  if (!input.canSeeSource && state.target?.tab === 'quelle') return { ...state, target: null }
  return state
}

function stateOf(input: StateInput): LibraryState {
  const { library, newestRun } = input
  if (input.erasureRequestedAt) {
    return {
      word: 'Wird gelöscht',
      tone: 'error',
      detail: formatDay(input.erasureRequestedAt),
      target: null,
    }
  }
  if (input.succession) {
    return {
      word: 'Nachfolge offen',
      tone: 'warning',
      detail: null,
      target: { tab: 'freigaben' },
    }
  }
  if (library.sourceType === 'UPLOAD') {
    const quota = library.storageQuotaBytes
    const full =
      input.uploadQuotaRejected ||
      (quota != null && quota > 0 && (library.storageUsedBytes ?? 0) >= quota)
    if (full) return { word: 'Kontingent voll', tone: 'error', detail: null, target: null }
    // uploaded documents still being processed: the same count as the documents' states
    if (input.pendingDocuments > 0) {
      const done = input.documentCount - input.pendingDocuments
      return {
        word: 'Läuft',
        tone: 'neutral',
        detail: [
          `${done.toLocaleString('de-DE')} von ${input.documentCount.toLocaleString('de-DE')}`,
          ...(input.failedDocuments > 0
            ? [`${input.failedDocuments.toLocaleString('de-DE')} fehlgeschlagen`]
            : []),
        ].join(' · '),
        target: { tab: 'dokumente', params: { status: 'PENDING' } },
      }
    }
    if (input.failedDocuments > 0) {
      return {
        word: 'Teilweise fehlgeschlagen',
        tone: 'warning',
        detail: `${input.failedDocuments.toLocaleString('de-DE')} fehlgeschlagen`,
        target: { tab: 'dokumente', params: { status: 'FAILED' } },
      }
    }
    if (input.documentCount === 0) {
      return { word: 'Noch kein Inhalt', tone: 'neutral', detail: null, target: null }
    }
    return {
      word: 'Hochgeladen',
      tone: 'success',
      detail: formatDay(library.updatedAt),
      target: null,
    }
  }
  if (input.runningPercent !== null) {
    return { word: 'Läuft', tone: 'neutral', detail: `${input.runningPercent} %`, target: LAEUFE }
  }
  const block = library.sourceBlock
  if (block?.reason === 'EXPIRED' || block?.reason === 'NOT_CONNECTED') {
    return { word: 'Zustimmung abgelaufen', tone: 'error', detail: null, target: VERBINDUNG }
  }
  if (!newestRun) {
    return { word: 'Noch nie indiziert', tone: 'neutral', detail: null, target: LAEUFE }
  }
  const word = runWord(newestRun, input.signsIn)
  const day = formatDay(newestRun.startedAt)
  switch (word) {
    case 'Abgeschlossen':
      return { word: 'Aktuell', tone: 'success', detail: day, target: LAEUFE }
    case 'Anmeldung abgelehnt':
    case 'Nicht erreichbar':
    case 'Zustimmung abgelaufen':
      return { word, tone: 'error', detail: day, target: VERBINDUNG }
    default:
      return { word, tone: runTone(word), detail: day, target: LAEUFE }
  }
}

/** The search of the page that leads to a state's cause. */
export function stateTargetHref(target: StateTarget): string {
  const params = new URLSearchParams(target.params)
  if (target.tab !== 'dokumente') params.set('tab', target.tab)
  if (target.anchor === 'laeufe') params.set('lauf', 'neu')
  const query = params.toString()
  return `${query ? `?${query}` : ''}${target.anchor ? `#${target.anchor}` : ''}`
}
