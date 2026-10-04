import { useEffect, useState } from 'react'
import { listMyConnectedAccounts } from '../services/connectedAccountApi'

/**
 * Whether the library runs on one of the caller's own connected accounts, i.e. is their private
 * library. Asked only while `ask` holds; `false` until the answer is in and on any failure.
 */
export function useRunsOnOwnAccount(libraryId: string, ask: boolean): boolean {
  const [answer, setAnswer] = useState<{ libraryId: string; runs: boolean } | null>(null)

  useEffect(() => {
    if (!ask) return
    let cancelled = false
    void listMyConnectedAccounts()
      .then((overview) => {
        if (cancelled) return
        setAnswer({
          libraryId,
          runs: overview.accounts.some((account) =>
            account.usedBy.some((library) => library.id === libraryId),
          ),
        })
      })
      .catch(() => {
        if (!cancelled) setAnswer({ libraryId, runs: false })
      })
    return () => {
      cancelled = true
    }
  }, [libraryId, ask])

  return ask && answer?.libraryId === libraryId && answer.runs
}
