import { useEffect, useRef, useState } from 'react'
import type { Dispatch, SetStateAction } from 'react'
import { useDocumentStore } from '../stores/documentStore'

/**
 * The search field of the document list: its value and the debounced (300 ms) load of the first
 * page for the typed query. A pending debounce is dropped on clearSearch, on a library change and
 * on unmount, so it never fires for a list the person has already left.
 */
export function useDocumentSearch(
  libraryId: string,
  setSelectedDocumentIds: Dispatch<SetStateAction<string[]>>,
) {
  const loadDocuments = useDocumentStore((s) => s.loadDocuments)
  const [searchInput, setSearchInput] = useState('')
  const searchDebounceRef = useRef<ReturnType<typeof setTimeout>>(undefined)

  useEffect(() => {
    return () => {
      // A pending debounce would otherwise fire for the previous library.
      if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    }
  }, [libraryId])

  function clearSearch() {
    setSearchInput('')
    if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
  }

  // The selection is bound to the list the person is looking at - a folder, search or page
  // change drops it, so a Sammelaktion never acts on documents nobody sees.
  function handleSearchChange(value: string) {
    setSearchInput(value)
    setSelectedDocumentIds([])
    if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    searchDebounceRef.current = setTimeout(() => {
      void loadDocuments(libraryId, { page: 0, q: value })
    }, 300)
  }

  return { searchInput, clearSearch, handleSearchChange }
}
