import { useEffect, useState } from 'react'
import { listConnectionProfileOptions } from '../services/connectionProfileApi'
import type { ConnectionProfileOption, SourceTypeKey } from '../types/api'

export interface ConnectionProfileOptionsState {
  options: ConnectionProfileOption[]
  error: string | null
  /** `true` once the answer for the asked type is in, successful or not. */
  loaded: boolean
}

interface Answer extends ConnectionProfileOptionsState {
  sourceType: SourceTypeKey
}

const NOT_ASKED: ConnectionProfileOptionsState = { options: [], error: null, loaded: false }

/**
 * The profiles a library of `sourceType` may be connected through, loaded whenever the type
 * changes; `null` asks for nothing. An answer for a type asked before counts as not loaded.
 */
export function useConnectionProfileOptions(
  sourceType: SourceTypeKey | null,
): ConnectionProfileOptionsState {
  const [answer, setAnswer] = useState<Answer | null>(null)

  useEffect(() => {
    if (sourceType === null) return
    let cancelled = false
    void listConnectionProfileOptions(sourceType)
      .then((options) => {
        if (!cancelled) setAnswer({ sourceType, options, error: null, loaded: true })
      })
      .catch((err) => {
        if (cancelled) return
        setAnswer({
          sourceType,
          options: [],
          error: err instanceof Error ? err.message : 'Die Zugänge konnten nicht geladen werden.',
          loaded: true,
        })
      })
    return () => {
      cancelled = true
    }
  }, [sourceType])

  if (sourceType === null || answer === null || answer.sourceType !== sourceType) return NOT_ASKED
  return answer
}
