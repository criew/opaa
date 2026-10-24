import { useCallback, useEffect, useState } from 'react'
import { listSourceTypes } from './sourceTypeApi'
import type { SourceTypeDescriptor } from '@/shared/api/types'

export interface SourceTypesState {
  sourceTypes: SourceTypeDescriptor[]
  error: string | null
  /** `true` once the answer is in, successful or not. */
  loaded: boolean
  /** Asks the backend again, e.g. after a failed load. */
  reload: () => void
}

/**
 * The source types the backend has a connector for (ADR-0038), loaded on mount and again on `reload` - the tiles
 * of the creation wizard. Which of them the frontend can configure decides its form registry.
 */
export function useSourceTypes(): SourceTypesState {
  const [state, setState] = useState<Omit<SourceTypesState, 'reload'>>({
    sourceTypes: [],
    error: null,
    loaded: false,
  })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    void listSourceTypes()
      .then((sourceTypes) => {
        if (!cancelled) setState({ sourceTypes, error: null, loaded: true })
      })
      .catch((err) => {
        if (cancelled) return
        setState({
          sourceTypes: [],
          error: err instanceof Error ? err.message : 'Quellarten konnten nicht geladen werden',
          loaded: true,
        })
      })
    return () => {
      cancelled = true
    }
  }, [attempt])

  const reload = useCallback(() => {
    setState((prev) => ({ ...prev, error: null, loaded: false }))
    setAttempt((n) => n + 1)
  }, [])

  return { ...state, reload }
}
