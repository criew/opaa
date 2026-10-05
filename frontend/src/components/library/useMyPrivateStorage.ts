import { useEffect, useState } from 'react'
import type { PrivateStorageUsageResponse } from '../../types/api'
import { getMyPrivateStorage } from '../../services/privateStorageApi'

/**
 * The caller's own use across her private libraries, loaded only while `enabled`. A failed read
 * leaves it `null`: the figure is an aid, never a blocker of the page around it.
 */
export function useMyPrivateStorage(
  enabled: boolean,
  refreshToken = 0,
): PrivateStorageUsageResponse | null {
  const [usage, setUsage] = useState<PrivateStorageUsageResponse | null>(null)

  useEffect(() => {
    if (!enabled) return
    let current = true
    getMyPrivateStorage()
      .then((loaded) => {
        if (current) setUsage(loaded)
      })
      .catch(() => {
        if (current) setUsage(null)
      })
    return () => {
      current = false
    }
  }, [enabled, refreshToken])

  return enabled ? usage : null
}
