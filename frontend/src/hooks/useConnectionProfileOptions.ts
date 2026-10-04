import { useEffect, useState } from 'react'
import { listConnectionProfileOptions } from '../services/connectionProfileApi'
import { apiErrorStatus } from '../services/apiErrorDetails'
import type { ConnectionProfileOption, SourceTypeKey } from '../types/api'

export interface ConnectionProfileOptionsState {
  options: ConnectionProfileOption[]
  error: string | null
  /** The caller may not read profiles at all (no connector right in any scope) - not transient. */
  forbidden: boolean
  /** `true` once the answer for the asked type is in, successful or not. */
  loaded: boolean
}

interface Answer extends ConnectionProfileOptionsState {
  asked: string
}

const NOT_ASKED: ConnectionProfileOptionsState = {
  options: [],
  error: null,
  forbidden: false,
  loaded: false,
}

/**
 * The profiles a library of `sourceType` may be connected through, loaded whenever the type
 * changes; `null` asks for nothing. With `libraryId` they are the ones that library's managers may
 * move it to - also without the right to create a library. An answer asked before counts as not
 * loaded.
 */
export function useConnectionProfileOptions(
  sourceType: SourceTypeKey | null,
  libraryId?: string,
): ConnectionProfileOptionsState {
  const [answer, setAnswer] = useState<Answer | null>(null)
  const asked = `${sourceType}/${libraryId ?? ''}`

  useEffect(() => {
    if (sourceType === null) return
    let cancelled = false
    void (
      libraryId
        ? listConnectionProfileOptions(sourceType, libraryId)
        : listConnectionProfileOptions(sourceType)
    )
      .then((options) => {
        if (!cancelled) {
          setAnswer({ asked, options, error: null, forbidden: false, loaded: true })
        }
      })
      .catch((err) => {
        if (cancelled) return
        setAnswer({
          asked,
          options: [],
          error: err instanceof Error ? err.message : 'Die Zugänge konnten nicht geladen werden.',
          forbidden: apiErrorStatus(err) === 403,
          loaded: true,
        })
      })
    return () => {
      cancelled = true
    }
  }, [sourceType, libraryId, asked])

  if (sourceType === null || answer === null || answer.asked !== asked) return NOT_ASKED
  return answer
}

/** What a person without any connector right is told about profiles, naming who grants it. */
export const PROFILES_FORBIDDEN_NOTICE =
  'Zugänge kann nur abrufen, wer Konnektorbibliotheken anlegen darf. Dieses Anlegerecht erteilt die Systemverwaltung.'
