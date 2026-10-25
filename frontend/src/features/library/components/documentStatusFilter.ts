import type { DocumentStatus } from '@/shared/api/types'
import type { DocumentStatusCounts } from '../stores/documentStore'
import { DOCUMENT_STATUS_WORDS } from './indexingLabels'

const FILTER_STATUSES: readonly DocumentStatus[] = ['FAILED', 'PENDING']

/** Below this many document rows in the library no list filter appears (guidelines 5.3). */
const MIN_ROWS = 5

/**
 * The options of the toolbar's status filter, each with its number of document rows. A state is
 * offered only from five rows of the whole library on and while it shortens the list - some rows
 * are in it, but not all (guidelines 5.3); the active one stays offered, so the select can still
 * show and clear it.
 */
export function statusFilterOptions(
  counts: DocumentStatusCounts | null | undefined,
  active: DocumentStatus | null,
): Array<[DocumentStatus, string]> {
  const total = counts ? counts.FAILED + counts.PENDING + counts.INDEXED : 0
  return FILTER_STATUSES.filter(
    (status) =>
      status === active ||
      (counts != null && total >= MIN_ROWS && counts[status] > 0 && counts[status] < total),
  ).map((status) => [
    status,
    counts
      ? `${DOCUMENT_STATUS_WORDS[status]} (${counts[status].toLocaleString('de-DE')})`
      : DOCUMENT_STATUS_WORDS[status],
  ])
}
