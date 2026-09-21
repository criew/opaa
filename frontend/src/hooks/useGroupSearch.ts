import { useEffect, useRef, useState } from 'react'
import { searchSelectableGroups } from '../services/api'
import type { SelectableGroupResponse } from '../types/api'

const SEARCH_DEBOUNCE_MS = 300
// Spiegelt die Untergrenze von GroupService#searchSelectableGroups: eine kürzere Eingabe verlässt
// den Browser gar nicht erst, der Dienst antwortete ohnehin mit einer leeren Liste.
const MIN_QUERY_LENGTH = 2

interface UseGroupSearchResult {
  query: string
  setQuery: (value: string) => void
  groups: SelectableGroupResponse[]
  isLoading: boolean
  error: string | null
}

/**
 * Die serverseitige Gruppensuche der Subjekt-Auswahl (#1820) — dieselbe Mechanik wie
 * {@link useUserSearch}: entprellt, mit eigener Kennzeichnung von „lädt noch" und „ist
 * fehlgeschlagen", und mit einem Abgleich der Anfragenummer, damit eine langsame Antwort auf einen
 * früheren Tastendruck keine veralteten Treffer einblendet.
 *
 * Welche Gruppen erscheinen, entscheidet allein der Dienst; dieser Hook filtert nichts nach.
 */
export function useGroupSearch(): UseGroupSearchResult {
  const [query, setQueryState] = useState('')
  const [groups, setGroups] = useState<SelectableGroupResponse[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(undefined)
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
          if (requestIdRef.current !== requestId) return
          setGroups(result)
          setIsLoading(false)
        })
        .catch((err) => {
          if (requestIdRef.current !== requestId) return
          setGroups([])
          setIsLoading(false)
          setError(err instanceof Error ? err.message : 'Die Gruppensuche ist fehlgeschlagen.')
        })
    }, SEARCH_DEBOUNCE_MS)
  }

  return { query, setQuery, groups, isLoading, error }
}

export const GROUP_SEARCH_MIN_QUERY_LENGTH = MIN_QUERY_LENGTH
