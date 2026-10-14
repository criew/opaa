import { useCallback, useRef, useState } from 'react'
import type { DirectorySyncPendingPlanResponse } from '@/shared/api/types'
import { getPendingPlan } from '../api/directorySyncApi'

/** The state of one provider's plan on this page; no entry means it is being loaded. */
export type PlanState =
  { kind: 'LOADED'; plan: DirectorySyncPendingPlanResponse } | { kind: 'NONE' } | { kind: 'FAILED' }

export interface PendingPlans {
  plans: Readonly<Record<string, PlanState>>
  /** Loads the plan the status names, unless this page already loaded that plan id. */
  ensure: (providerId: string, pendingPlanId: string | null) => void
  /** Loads it again after a failed attempt. */
  retry: (providerId: string, pendingPlanId: string | null) => void
  /** Puts a plan in place the page got another way - a re-presented plan, or none after discarding. */
  replace: (providerId: string, plan: DirectorySyncPendingPlanResponse | null) => void
}

/**
 * The pending plans the page has loaded, per provider. Every retrieval of a plan is an audit entry
 * (ADR-0036, Entscheidung 9), so a plan is loaded once per id and page - neither a status reload
 * naming the same plan nor closing and reopening the side panel fetches it again. A plan the
 * server no longer has calls `onGone`, so the page can drop it with a fresh status.
 */
export function usePendingPlans(onGone: () => void): PendingPlans {
  const [plans, setPlans] = useState<Record<string, PlanState>>({})
  const loadedPlanIds = useRef(new Map<string, string | null>())

  const put = useCallback((providerId: string, state: PlanState | undefined) => {
    setPlans((current) => {
      const next = { ...current }
      if (state) next[providerId] = state
      else delete next[providerId]
      return next
    })
  }, [])

  const replace = useCallback(
    (providerId: string, plan: DirectorySyncPendingPlanResponse | null) => {
      loadedPlanIds.current.set(providerId, plan?.id ?? null)
      put(providerId, plan ? { kind: 'LOADED', plan } : { kind: 'NONE' })
    },
    [put],
  )

  const ensure = useCallback(
    (providerId: string, pendingPlanId: string | null) => {
      if (!pendingPlanId || loadedPlanIds.current.get(providerId) === pendingPlanId) return
      loadedPlanIds.current.set(providerId, pendingPlanId)
      put(providerId, undefined)
      void getPendingPlan(providerId)
        .then((loaded) => {
          if (loaded) {
            replace(providerId, loaded)
            return
          }
          // Remembering the gone id keeps a status that still names it from fetching it again.
          put(providerId, { kind: 'NONE' })
          onGone()
        })
        .catch(() => {
          loadedPlanIds.current.delete(providerId)
          put(providerId, { kind: 'FAILED' })
        })
    },
    [put, replace, onGone],
  )

  const retry = useCallback(
    (providerId: string, pendingPlanId: string | null) => {
      loadedPlanIds.current.delete(providerId)
      ensure(providerId, pendingPlanId)
    },
    [ensure],
  )

  return { plans, ensure, retry, replace }
}
