import { useEffect, useRef, useState } from 'react'
import { searchSelectableGroups } from '../api/groupLookupApi'
import type { SelectableGroupResponse } from '@/shared/api/types'

const SEARCH_DEBOUNCE_MS = 300
// Mirrors the lower bound of GroupService#searchSelectableGroups: a shorter input does not
// even leave the browser, the service would answer with an empty list anyway.
const MIN_QUERY_LENGTH = 2

interface UseGroupSearchResult {
  query: string
  setQuery: (value: string) => void
  groups: SelectableGroupResponse[]
  isLoading: boolean
  error: string | null
}

/**
 * The server-side group search of the subject picker — the same mechanics as
 * {@link useUserSearch}: debounced, with its own marking of „still loading" and „has
 * failed", and with a check of the request number, so that a slow response to an
 * earlier keystroke does not show outdated matches.
 *
 * Only the service decides which groups appear; this hook filters nothing afterwards.
 */
export function useGroupSearch(): UseGroupSearchResult {
  const [query, setQueryState] = useState('')
  const [groups, setGroups] = useState<SelectableGroupResponse[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(undefined)
  const requestIdRef = useRef(0)
  // After unmounting (e.g. when the dialog closes while a search is running) the response
  // no longer sets state - the request number alone only tells old from new requests.
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      if (debounceRef.current) clearTimeout(debounceRef.current)
    }
  }, [])

  function setQuery(value: string) {
    setQueryState(value)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    const trimmed = value.trim()
    if (trimmed.length < MIN_QUERY_LENGTH) {
      requestIdRef.current += 1
      setGroups([])
      setIsLoading(false)
      setError(null)
      return
    }
    setIsLoading(true)
    setError(null)
    const requestId = ++requestIdRef.current
    debounceRef.current = setTimeout(() => {
      void searchSelectableGroups(trimmed)
        .then((result) => {
          if (!mountedRef.current || requestIdRef.current !== requestId) return
          setGroups(result)
          setIsLoading(false)
        })
        .catch((err) => {
          if (!mountedRef.current || requestIdRef.current !== requestId) return
          setGroups([])
          setIsLoading(false)
          setError(err instanceof Error ? err.message : 'Die Gruppensuche ist fehlgeschlagen.')
        })
    }, SEARCH_DEBOUNCE_MS)
  }

  return { query, setQuery, groups, isLoading, error }
}

export const GROUP_SEARCH_MIN_QUERY_LENGTH = MIN_QUERY_LENGTH
