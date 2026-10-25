import { useSearchParams } from 'react-router'
import type { DocumentStatus } from '@/shared/api/types'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import { DEFAULT_PAGE_SIZE, useDocumentStore } from '../stores/documentStore'

/** The states the toolbar filters by; a finished document is what the unfiltered list shows. */
const FILTERED_STATUSES: readonly DocumentStatus[] = ['FAILED', 'PENDING']

/**
 * The URL state of the Reiter „Dokumente": the open folder, the Pflege-Anker's filter and the
 * status filter, which the head's state links to (?status=FAILED). The two filters are
 * bibliotheksweit and exclusive, since the anchor only lists indexed documents. Every navigation
 * calls onLeaveList first, which drops the search and the selection of the list the
 * person is leaving; the section's load effect reacts to the changed URL.
 */
export function useDocumentsUrlState(libraryId: string, onLeaveList: () => void) {
  const loadDocuments = useDocumentStore((s) => s.loadDocuments)

  // The currently open folder is URL state (?folder=<id>) - a reload or a bookmarked link
  // lands back in the same folder instead of always resetting to the library's root.
  const [searchParams, setSearchParams] = useSearchParams()
  const folderIdParam = searchParams.get('folder')
  // The Pflege-Anker's filter is URL state (?missingField=<core field key>).
  // Without metadata (ADR-0044) a Pflege-Anker link in the URL lists the plain documents.
  const metadataEnabled = useFeatureEnabled('metadata')
  const missingFieldParam = metadataEnabled ? searchParams.get('missingField') : null
  const rawStatus = searchParams.get('status')
  const statusParam: DocumentStatus | null =
    FILTERED_STATUSES.find((status) => status === rawStatus) ?? null

  // Opens (or leaves) the status filter's list - bibliotheksweit like the anchor's.
  function setStatusFilter(status: DocumentStatus | null) {
    onLeaveList()
    const next = new URLSearchParams(searchParams)
    next.delete('folder')
    next.delete('missingField')
    if (status) {
      next.set('status', status)
    } else {
      next.delete('status')
    }
    setSearchParams(next)
  }

  // Opens (or leaves) the anchor's worklist - the list is bibliotheksweit, so an open
  // folder and a running search are dropped with it, and the selection starts empty.
  function setMissingFieldFilter(fieldKey: string | null) {
    onLeaveList()
    const next = new URLSearchParams(searchParams)
    next.delete('folder')
    next.delete('status')
    if (fieldKey) {
      next.set('missingField', fieldKey)
    } else {
      next.delete('missingField')
    }
    setSearchParams(next)
  }

  // Navigates into a folder (or back to the root with null) by changing the URL's folder
  // param - the section's load effect reacts to that change. Also clears any in-flight search.
  function navigateToFolder(folderId: string | null) {
    onLeaveList()
    // A search hit's folderPath link can name the very folder already
    // loaded - the URL then does not change, so the load effect never fires and the stale,
    // still-bibliotheksweit results would keep showing. Reloading explicitly is the only way.
    if (folderId === folderIdParam && !missingFieldParam && !statusParam) {
      void loadDocuments(libraryId, { page: 0, size: DEFAULT_PAGE_SIZE, q: '', folderId })
      return
    }
    const next = new URLSearchParams(searchParams)
    if (folderId) {
      next.set('folder', folderId)
    } else {
      next.delete('folder')
    }
    // Browsing a folder leaves the bibliotheksweite lists of both filters.
    next.delete('missingField')
    next.delete('status')
    setSearchParams(next)
  }

  return {
    searchParams,
    setSearchParams,
    folderIdParam,
    metadataEnabled,
    missingFieldParam,
    setMissingFieldFilter,
    statusParam,
    // a ?status= that names no filtered state, e.g. from a mistyped link
    invalidStatusParam: rawStatus !== null && statusParam === null,
    setStatusFilter,
    navigateToFolder,
  }
}
