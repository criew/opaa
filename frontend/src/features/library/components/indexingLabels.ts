import type {
  DocumentStatus,
  IndexingRunEventCategory,
  IndexingRunMode,
  IndexingTriggerSource,
} from '@/shared/api/types'

const documentStatusLabels: Record<DocumentStatus, string> = {
  PENDING: 'wird verarbeitet',
  INDEXED: 'indiziert',
  FAILED: 'fehlgeschlagen',
}

export function documentStatusLabel(status: DocumentStatus | string | undefined): string {
  if (!status) return ''
  return documentStatusLabels[status as DocumentStatus] ?? status
}

// German, understandable categories for a skipped/rejected item or error in a run's
// protocol - matches io.opaa.indexing.job.IndexingEventCategory's own Javadoc one-to-one.
const indexingRunEventCategoryLabels: Record<IndexingRunEventCategory, string> = {
  REJECTED: 'Abgewiesen',
  UNREACHABLE: 'Nicht erreichbar',
  UNSUPPORTED_FORMAT: 'Format nicht unterstützt',
  ALLOWLIST: 'Allowlist',
  ERROR: 'Fehler',
  // Indexed anyway, only the deviation between the file's own extension and its detected
  // content is reported here.
  FORMAT_MISMATCH: 'Endung weicht vom Inhalt ab',
  // Indexed anyway, without a malware scan: the scanner could not judge it (policy ACCEPT).
  UNSCANNED: 'Ohne Virenprüfung aufgenommen',
  SCHEDULE_SKIPPED: 'Geplanter Lauf übersprungen',
  // The document no longer exists at its source and was removed at the end of a
  // successful, complete run - a note about the removal, not a skip/reject/error of this run.
  REMOVED: 'In der Quelle entfernt',
  // The source throttled the run and it slowed down instead of failing - one summary note.
  RATE_LIMITED: 'Ratenbegrenzung',
  BUDGET_EXHAUSTED: 'Anfragebudget erschöpft',
  // The run's own figures, one note per run for a source that counts them.
  SUMMARY: 'Kennzahlen',
}

// ADR-0023, Entscheidung 4: the Betriebsart of a run - whether its listing was complete
// (and could remove what it did not meet again) or only picked up changes.
const indexingRunModeLabels: Record<IndexingRunMode, string> = {
  FULL: 'Vollabgleich',
  INCREMENTAL: 'Inkrementell',
  // ADR-0027, Entscheidung 3: the run an S3 event notification starts - reported keys
  // checked one by one, never a listing.
  EVENT: 'Ereignislauf',
}

export function indexingRunModeLabel(mode: IndexingRunMode | string | undefined): string {
  if (!mode) return ''
  return indexingRunModeLabels[mode as IndexingRunMode] ?? mode
}

const indexingTriggerSourceLabels: Record<IndexingTriggerSource, string> = {
  MANUAL: 'manuell gestartet',
  SCHEDULED: 'per Zeitplan',
  WEBHOOK: 'per Webhook',
}

/** Who started a run - shown in the run history beside the run mode. */
export function indexingTriggerSourceLabel(
  source: IndexingTriggerSource | string | undefined,
): string {
  if (!source) return ''
  return indexingTriggerSourceLabels[source as IndexingTriggerSource] ?? source
}

export function indexingRunEventCategoryLabel(
  category: IndexingRunEventCategory | string | undefined,
): string {
  if (!category) return ''
  return indexingRunEventCategoryLabels[category as IndexingRunEventCategory] ?? category
}
