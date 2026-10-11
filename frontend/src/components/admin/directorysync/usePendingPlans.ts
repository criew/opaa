import { useCallback, useRef, useState } from 'react'
import type { DirectorySyncPendingPlanResponse } from '../../../types/api'
import { getPendingPlan } from '../../../services/directorySyncApi'

export interface PendingPlans {
  plans: Readonly<Record<string, DirectorySyncPendingPlanResponse | null>>
  /** Loads the plan the status names, unless this page already loaded that plan id. */
  ensure: (providerId: string, pendingPlanId: string | null) => void
  /** Puts a plan in place the page got another way - a re-presented plan, or none after discarding. */
  replace: (providerId: string, plan: DirectorySyncPendingPlanResponse | null) => void
}

/**
 * The pending plans the page has loaded, per provider. Every retrieval of a plan is an audit entry
 * (ADR-0036, Entscheidung 9), so a plan is loaded once per id and page - neither a status reload
 * naming the same plan nor closing and reopening the side panel fetches it again.
 */
export function usePendingPlans(): PendingPlans {
  const [plans, setPlans] = useState<Record<string, DirectorySyncPendingPlanResponse | null>>({})
  const loadedPlanIds = useRef(new Map<string, string | null>())

  const replace = useCallback(
    (providerId: string, plan: DirectorySyncPendingPlanResponse | null) => {
      loadedPlanIds.current.set(providerId, plan?.id ?? null)
      setPlans((current) => ({ ...current, [providerId]: plan }))
    },
    [],
  )

  const ensure = useCallback(
    (providerId: string, pendingPlanId: string | null) => {
      if (!pendingPlanId || loadedPlanIds.current.get(providerId) === pendingPlanId) return
      loadedPlanIds.current.set(providerId, pendingPlanId)
      void getPendingPlan(providerId)
        .then((loaded) => replace(providerId, loaded))
        .catch(() => {
          loadedPlanIds.current.delete(providerId)
          setPlans((current) => ({ ...current, [providerId]: null }))
        })
    },
    [replace],
  )

  return { plans, ensure, replace }
}
