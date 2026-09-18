import { useCallback, useEffect, useRef, useState } from 'react'
import axios from 'axios'
import { ChatSearchRateLimitedError, searchSpaceChats } from '../services/api'
import type { ChatSearchHit } from '../types/api'

/** The backend's bounds for a term, counted in characters (code points) after trimming. */
export const CHAT_SEARCH_MIN_LENGTH = 3
export const CHAT_SEARCH_MAX_LENGTH = 200
export const CHAT_SEARCH_PAGE_SIZE = 20
const DEBOUNCE_MS = 300

/** Router state with which the sidebar hands a term over; never part of the page's address. */
export interface ChatSearchHandover {
  chatSearchTerm: string
}

export function isSearchableTerm(term: string): boolean {
  return [...term.trim()].length >= CHAT_SEARCH_MIN_LENGTH
}

interface SearchRequest {
  term: string
  seq: number
}

interface SearchResult {
  seq: number
  term: string
  hits: ChatSearchHit[]
  page: number
  hasMore: boolean
  error: string | null
}

function messageOf(err: unknown): string {
  if (err instanceof ChatSearchRateLimitedError) {
    const wait = err.retryAfterSeconds
    return `Zu viele Suchanfragen in kurzer Zeit. Bitte ${
      wait === null ? 'gleich' : wait === 1 ? 'in 1 Sekunde' : `in ${wait} Sekunden`
    } erneut versuchen.`
  }
  return err instanceof Error ? err.message : 'Die Chatsuche ist fehlgeschlagen.'
}

export interface ChatSearch {
  query: string
  /** Sets the field's text; a searchable term is searched after a short typing pause. */
  changeQuery: (value: string) => void
  /** Searches the current text at once (Enter), also to repeat a failed search. */
  submit: () => void
  /** Puts `value` into the field and searches it at once. */
  searchNow: (value: string) => void
  loadMore: () => void
  isLoading: boolean
  isLoadingMore: boolean
  result: SearchResult | null
}

/**
 * The chat search of one space. The term lives in component state only - it is never persisted,
 * never put into a URL - and a newer search aborts every older request still in flight, so a late
 * answer can never replace the result of the current term.
 */
export function useChatSearch(spaceId: string): ChatSearch {
  const [query, setQuery] = useState('')
  const [request, setRequest] = useState<SearchRequest | null>(null)
  const [result, setResult] = useState<SearchResult | null>(null)
  const [isLoadingMore, setIsLoadingMore] = useState(false)
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(undefined)
  const moreRef = useRef<AbortController | null>(null)

  useEffect(() => () => clearTimeout(debounceRef.current), [])

  useEffect(() => {
    if (request === null) return
    const controller = new AbortController()
    searchSpaceChats(
      spaceId,
      { query: request.term, page: 0, pageSize: CHAT_SEARCH_PAGE_SIZE },
      controller.signal,
    ).then(
      (response) =>
        setResult({
          ...request,
          hits: response.hits,
          page: 0,
          hasMore: response.hasMore,
          error: null,
        }),
      (err: unknown) => {
        if (controller.signal.aborted || axios.isCancel(err)) return
        setResult({ ...request, hits: [], page: 0, hasMore: false, error: messageOf(err) })
      },
    )
    return () => {
      controller.abort()
      moreRef.current?.abort()
    }
  }, [spaceId, request])

  const issue = useCallback((term: string) => {
    clearTimeout(debounceRef.current)
    setIsLoadingMore(false)
    const trimmed = term.trim()
    setRequest((current) =>
      isSearchableTerm(trimmed) ? { term: trimmed, seq: (current?.seq ?? 0) + 1 } : null,
    )
  }, [])

  function changeQuery(value: string) {
    setQuery(value)
    clearTimeout(debounceRef.current)
    if (!isSearchableTerm(value)) {
      issue(value)
      return
    }
    debounceRef.current = setTimeout(() => issue(value), DEBOUNCE_MS)
  }

  const searchNow = useCallback(
    (value: string) => {
      setQuery(value)
      issue(value)
    },
    [issue],
  )

  function loadMore() {
    if (!result || result.error || !result.hasMore || result.seq !== request?.seq) return
    const current = result
    const controller = new AbortController()
    moreRef.current?.abort()
    moreRef.current = controller
    setIsLoadingMore(true)
    searchSpaceChats(
      spaceId,
      { query: current.term, page: current.page + 1, pageSize: CHAT_SEARCH_PAGE_SIZE },
      controller.signal,
    ).then(
      (response) => {
        if (controller.signal.aborted) return
        setIsLoadingMore(false)
        setResult((latest) =>
          latest?.seq === current.seq
            ? {
                ...latest,
                hits: [...latest.hits, ...response.hits],
                page: current.page + 1,
                hasMore: response.hasMore,
              }
            : latest,
        )
      },
      (err: unknown) => {
        if (controller.signal.aborted || axios.isCancel(err)) return
        setIsLoadingMore(false)
        setResult((latest) =>
          latest?.seq === current.seq ? { ...latest, error: messageOf(err) } : latest,
        )
      },
    )
  }

  return {
    query,
    changeQuery,
    submit: () => issue(query),
    searchNow,
    loadMore,
    isLoading: request !== null && result?.seq !== request.seq,
    isLoadingMore,
    result: request !== null && result?.seq === request.seq ? result : null,
  }
}
