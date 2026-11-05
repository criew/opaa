import { useCallback, useEffect, useState } from 'react'
import type { ServiceAccountResponse } from '@/shared/api/types'
import { listServiceAccounts } from '../../api/serviceAccountApi'

/** The service accounts of the installation; null while loading. */
export function useServiceAccounts() {
  const [accounts, setAccounts] = useState<ServiceAccountResponse[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(
    () =>
      listServiceAccounts()
        .then((loaded) => {
          setAccounts(loaded)
          setError(null)
        })
        .catch((err: unknown) => {
          setAccounts([])
          setError(
            err instanceof Error ? err.message : 'Die Dienstkonten konnten nicht geladen werden.',
          )
        }),
    [],
  )

  useEffect(() => {
    void reload()
  }, [reload])

  return { accounts, error, reload }
}
