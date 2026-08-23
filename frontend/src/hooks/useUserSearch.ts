import { useEffect, useRef, useState } from 'react'
import { getUserSummaries } from '../services/api'
import type { UserSummary } from '../types/api'

const SEARCH_DEBOUNCE_MS = 300
// Mirrors UserService#searchInOrganization's own minimum (backend/src/main/java/io/opaa/auth/
// UserService.java) - kept in sync manually since the two run in different languages/processes;
// a query shorter than this never leaves the browser, sparing a request the backend would answer
// with an empty list anyway.
const MIN_QUERY_LENGTH = 2

interface UseUserSearchResult {
  query: string
  setQuery: (value: string) => void
  users: UserSummary[]
  isLoading: boolean
  error: string | null
}

/**
 * Debounced, server-side user search backing the member/grant pickers on `SpaceManagementPage`,
 * `SpaceCreatePage`, `LibraryCreatePage` and `LibraryGrantsDialog` (#777).
 *
 * #778 review, finding 4: `GET /v1/users` now requires a query (min. `MIN_QUERY_LENGTH`
 * characters) and caps its result server-side - it no longer answers an unqualified "list
 * everyone" call on every picker mount, so this hook only issues a request once the caller has
 * typed enough, debounced so each keystroke does not fire its own request.
 *
 * #778 review, finding 1: `isLoading` and `error` are tracked separately - opening the picker and
 * acting immediately (before the in-flight request settles) is "still loading", not "failed to
 * load"; only a request that actually rejects sets `error`. A caller that only checked "the list
 * is empty" could not tell the two apart and showed a misleading permanent-looking failure message
 * for what was really just the network round trip.
 *
 * `setQuery` is invoked from the caller's `onInputChange` handler (an event, not an effect) and
 * kicks off the debounce/fetch itself - the same pattern `LibraryDetailPage#handleSearchChange`
 * already uses, and deliberately not a `useEffect` keyed on `query`: `react-hooks/set-state-in-
 * effect` flags exactly that shape (a `setState` called synchronously in an effect body ahead of
 * the actual async work), and there is nothing here for a `useEffect` to synchronize with an
 * external system on mount - every state change already originates from a discrete input event.
 */
export function useUserSearch(): UseUserSearchResult {
  const [query, setQueryState] = useState('')
  const [users, setUsers] = useState<UserSummary[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(undefined)
  // Distinguishes the most recently issued request from an earlier, still in-flight one - without
  // this, a slow response for an earlier keystroke could overwrite the result of a faster response
  // for a later one, flashing stale options in as the caller keeps typing.
  const requestIdRef = useRef(0)

  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current)
    }
  }, [])

  function setQuery(value: string) {
    setQueryState(value)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    const trimmed = value.trim()
    if (trimmed.length < MIN_QUERY_LENGTH) {
      requestIdRef.current += 1
      setUsers([])
      setIsLoading(false)
      setError(null)
      return
    }
    setIsLoading(true)
    setError(null)
    const requestId = ++requestIdRef.current
    debounceRef.current = setTimeout(() => {
      void getUserSummaries(trimmed)
        .then((result) => {
          if (requestIdRef.current !== requestId) return
          setUsers(result)
          setIsLoading(false)
        })
        .catch((err) => {
          if (requestIdRef.current !== requestId) return
          setUsers([])
          setIsLoading(false)
          setError(err instanceof Error ? err.message : 'Die Nutzersuche ist fehlgeschlagen.')
        })
    }, SEARCH_DEBOUNCE_MS)
  }

  return { query, setQuery, users, isLoading, error }
}
