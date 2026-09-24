import { useEffect, useState } from 'react'
import { getMyGroups } from '../services/api'
import type { GroupListResponse } from '../types/api'

export interface MyGroupsState {
  groups: GroupListResponse[]
  error: string | null
  /** `true` once the answer is in, successful or not. */
  loaded: boolean
}

/**
 * The caller's own groups, loaded once per mount - the choice of an owning group when an asset is
 * created. Only a member may make a group the owner; the backend checks that again.
 */
export function useMyGroups(): MyGroupsState {
  const [state, setState] = useState<MyGroupsState>({ groups: [], error: null, loaded: false })

  useEffect(() => {
    let cancelled = false
    void getMyGroups()
      .then((groups) => {
        if (!cancelled) setState({ groups, error: null, loaded: true })
      })
      .catch((err) => {
        if (cancelled) return
        setState({
          groups: [],
          error: err instanceof Error ? err.message : 'Gruppen konnten nicht geladen werden',
          loaded: true,
        })
      })
    return () => {
      cancelled = true
    }
  }, [])

  return state
}
