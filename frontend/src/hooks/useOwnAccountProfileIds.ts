import { useEffect, useState } from 'react'
import { listMyConnectedAccounts } from '../services/connectedAccountApi'

const NONE: readonly string[] = []

/**
 * The profiles the caller has a connected account on, in any state - the ones a private library
 * may run on. Asked once while `ask` holds; `null` until the answer is in, empty on any failure, so
 * nothing about private libraries shows without an account.
 */
export function useOwnAccountProfileIds(ask: boolean): readonly string[] | null {
  const [ids, setIds] = useState<readonly string[] | null>(null)

  useEffect(() => {
    if (!ask || ids !== null) return
    let cancelled = false
    void listMyConnectedAccounts()
      .then((overview) => {
        if (!cancelled) setIds(overview.accounts.map((account) => account.profileId))
      })
      .catch(() => {
        if (!cancelled) setIds(NONE)
      })
    return () => {
      cancelled = true
    }
  }, [ask, ids])

  return ask ? ids : NONE
}
