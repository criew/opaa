import type { IndexingRunResponse, LibraryResponse } from '@/shared/api/types'
import type { StatusTone } from '@/shared/ui/StatusLine'

/** The place a state word leads to: a row of the Reiter „Quelle“, the runs, or the documents. */
export type StateCause = 'connection' | 'scope' | 'schedule' | 'runs' | 'documents' | 'sharing'

/** The one state of a library: a word from a fixed list, one addition and where its cause lies. */
export interface LibraryState {
  word: string
  tone: StatusTone
  /** A date or a percentage, never both. */
  detail: string | null
  cause: StateCause | null
}

export interface LibraryStateInput {
  library: Pick<
    LibraryResponse,
    | 'sourceType'
    | 'sourceBlock'
    | 'succession'
    | 'lastScheduledRunsFailed'
    | 'storageQuotaBytes'
    | 'storageUsedBytes'
    | 'updatedAt'
    | 'documentCount'
  >
  erasing: boolean
  /** The status endpoint's view of the newest run. */
  status: {
    status: string
    documentCount: number
    documentsSkipped: number
    documentsFailed: number
    totalDocuments: number
    failureCategory: string | null
    unlistedScopeKeys: string[]
    timestamp: string | null
  }
  /** The newest run of the history; only managers load it. */
  newestRun: IndexingRunResponse | null
  /** Upload rejections of this session, as the document store keeps them. */
  uploadErrors: string[]
  /** What the loaded documents of an upload library show: failed or still processed ones. */
  uploadDocuments?: {
    failed: number
    pending: number
    /** The loaded page holds only part of the library: the counts are lower bounds. */
    partial: boolean
  }
  /** Whether the source signs in at all; a path or an open web directory has no sign-in. */
  signsIn?: boolean
}

/** „24.10.2026“. */
export function formatDay(value: string | null | undefined): string | null {
  if (!value) return null
  return new Date(value).toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

/** The running run's share of processed entries, from the same counts as its run row. */
export function runPercent(run: {
  documentCount: number
  documentsSkipped: number
  totalDocuments: number
}): number | null {
  if (run.totalDocuments <= 0) return null
  return Math.round(((run.documentCount + run.documentsSkipped) / run.totalDocuments) * 100)
}

const REJECTED_SIGN_IN = new Set(['AUTH', 'CREDENTIALS_REJECTED'])

/** A finished run counted as only partly done: failed documents, unread scope or cut short. */
export function runPartlyFailed(run: IndexingRunResponse): boolean {
  return (
    run.status === 'COMPLETED' &&
    (run.documentsFailed > 0 || Boolean(run.incomplete) || (run.unreadableScopeCount ?? 0) > 0)
  )
}

function isQuotaMessage(message: string): boolean {
  return /kontingent/i.test(message)
}

/**
 * The library's state in one word (pruefliste-detail D2), derived from one source: the newest run
 * as the run list shows it, the source block and the library's own markings. Kopf, Steckbrief and
 * run row therefore never disagree (D3).
 */
export function libraryStateOf(input: LibraryStateInput): LibraryState {
  const { library, erasing, status, newestRun, uploadErrors, signsIn = true } = input
  if (erasing) return { word: 'Wird gelöscht', tone: 'warning', detail: null, cause: null }
  if (library.succession) {
    return { word: 'Nachfolge offen', tone: 'warning', detail: null, cause: 'sharing' }
  }

  if (library.sourceType === 'UPLOAD') {
    const quota =
      library.storageQuotaBytes != null &&
      library.storageUsedBytes != null &&
      library.storageUsedBytes >= library.storageQuotaBytes
    // the documents tell the state; rejected files never became one and stay out of it. Without
    // a count from the backend the loaded page gives one, as a lower bound when there is more.
    const docs = input.uploadDocuments
    const failedCount =
      docs && docs.failed > 0
        ? `${docs.partial ? 'mind. ' : ''}${docs.failed} fehlgeschlagen`
        : null
    if (quota || uploadErrors.some(isQuotaMessage)) {
      return { word: 'Kontingent voll', tone: 'error', detail: failedCount, cause: 'documents' }
    }
    if (failedCount) {
      return {
        word: 'Teilweise fehlgeschlagen',
        tone: 'warning',
        detail: failedCount,
        cause: 'documents',
      }
    }
    if (docs && docs.pending > 0) {
      return { word: 'Läuft', tone: 'neutral', detail: null, cause: 'documents' }
    }
    if (!library.documentCount) {
      return { word: 'Noch kein Inhalt', tone: 'neutral', detail: null, cause: 'documents' }
    }
    return {
      word: 'Hochgeladen',
      tone: 'success',
      detail: formatDay(library.updatedAt),
      cause: null,
    }
  }

  if (status.status === 'RUNNING') {
    const percent = runPercent(status)
    return {
      word: 'Läuft',
      tone: 'neutral',
      detail: percent == null ? null : `${percent} %`,
      cause: 'runs',
    }
  }

  const block = library.sourceBlock
  if (block) {
    const consent = block.action === 'CONNECT_SOURCE' || block.action === 'CONNECT_OWN_ACCOUNT'
    if (block.reason === 'EXPIRED' && consent) {
      return { word: 'Zustimmung abgelaufen', tone: 'error', detail: null, cause: 'connection' }
    }
    if (block.reason === 'EXPIRED' || block.reason === 'NOT_CONNECTED') {
      return { word: 'Anmeldung abgelehnt', tone: 'error', detail: null, cause: 'connection' }
    }
    return { word: 'Fehlgeschlagen', tone: 'error', detail: null, cause: 'connection' }
  }

  const category = newestRun?.failureCategory ?? status.failureCategory
  const finished = newestRun ?? null
  const day = formatDay(finished?.startedAt ?? status.timestamp)
  const failed = finished ? finished.status === 'FAILED' : status.status === 'FAILED'
  if (failed && category && REJECTED_SIGN_IN.has(category)) {
    return signsIn
      ? { word: 'Anmeldung abgelehnt', tone: 'error', detail: day, cause: 'connection' }
      : { word: 'Fehlgeschlagen', tone: 'error', detail: day, cause: 'connection' }
  }
  if (category === 'QUOTA_EXHAUSTED') {
    return { word: 'Kontingent voll', tone: 'error', detail: day, cause: 'runs' }
  }
  if (failed || library.lastScheduledRunsFailed) {
    return { word: 'Fehlgeschlagen', tone: 'error', detail: day, cause: 'runs' }
  }
  const partly = finished
    ? runPartlyFailed(finished)
    : status.status === 'COMPLETED' && status.documentsFailed > 0
  if (partly || status.unlistedScopeKeys.length > 0) {
    return {
      word: 'Teilweise fehlgeschlagen',
      tone: 'warning',
      detail: day,
      cause: status.unlistedScopeKeys.length > 0 ? 'scope' : 'runs',
    }
  }
  if (status.status === 'IDLE' && !finished) {
    return { word: 'Noch nie indiziert', tone: 'neutral', detail: null, cause: 'runs' }
  }
  return { word: 'Aktuell', tone: 'success', detail: day, cause: null }
}
