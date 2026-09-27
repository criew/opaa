import { useEffect, useState } from 'react'
import { listSourceTypes } from '../services/api'
import type { SourceTypeDescriptor } from '../types/api'

export interface SourceTypesState {
  sourceTypes: SourceTypeDescriptor[]
  error: string | null
  /** `true` once the answer is in, successful or not. */
  loaded: boolean
}

/**
 * The source types the backend has a connector for (ADR-0038), loaded once per mount - the tiles
 * of the creation wizard. Which of them the frontend can configure decides its form registry.
 */
export function useSourceTypes(): SourceTypesState {
  const [state, setState] = useState<SourceTypesState>({
    sourceTypes: [],
    error: null,
    loaded: false,
  })

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
  }, [])

  return state
}
