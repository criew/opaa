import { useSearchParams } from 'react-router'
import type { DocumentStatus } from '@/shared/api/types'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import { DEFAULT_PAGE_SIZE, useDocumentStore } from '../stores/documentStore'

/**
 * The URL state of the Reiter „Dokumente": the open folder and the Pflege-Anker's filter. Both
 * navigations call onLeaveList first, which drops the search and the selection of the list the
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
  // The toolbar's status filter is URL state too (?status=FAILED); the head's state links to it.
  const rawStatus = searchParams.get('status')
  const statusParam: DocumentStatus | null =
    rawStatus === 'FAILED' || rawStatus === 'PENDING' || rawStatus === 'INDEXED' ? rawStatus : null

  function setStatusFilter(status: DocumentStatus | null) {
    onLeaveList()
    const next = new URLSearchParams(searchParams)
    next.delete('folder')
    if (status) next.set('status', status)
    else next.delete('status')
    setSearchParams(next)
  }

  // Opens (or leaves) the anchor's worklist - the list is bibliotheksweit, so an open
  // folder and a running search are dropped with it, and the selection starts empty.
  function setMissingFieldFilter(fieldKey: string | null) {
    onLeaveList()
    const next = new URLSearchParams(searchParams)
    next.delete('folder')
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
    if (folderId === folderIdParam && !missingFieldParam) {
      void loadDocuments(libraryId, { page: 0, size: DEFAULT_PAGE_SIZE, q: '', folderId })
      return
    }
    const next = new URLSearchParams(searchParams)
    if (folderId) {
      next.set('folder', folderId)
    } else {
      next.delete('folder')
    }
    // Browsing a folder leaves the anchor's (bibliotheksweite) worklist.
    next.delete('missingField')
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
    setStatusFilter,
    navigateToFolder,
  }
}
