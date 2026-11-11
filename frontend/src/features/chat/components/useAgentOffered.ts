import { useEffect, useState } from 'react'
import { useRuntimeSwitchStore } from '@/shared/session/runtimeSwitches'
import { listAvailableAgents } from '@/features/agent'

/**
 * Whether the agent is still on offer in the space - not locked, not unshared, agents still
 * switched on. Read anew for every `key` (the answer the agent waits on), so a lock since then is
 * noticed. False until the answer is in and when it fails: a plan is never confirmed to an agent
 * that cannot run it.
 */
export function useAgentOffered(
  spaceId: string | null,
  agentId: string | null,
  key: string | null,
): boolean {
  const agentsOn = useRuntimeSwitchStore((state) => state.agents)
  const [offered, setOffered] = useState<{ read: string; value: boolean } | null>(null)
  const read = `${spaceId}|${agentId}|${key}`

  useEffect(() => {
    if (!agentsOn || !spaceId || !agentId) return
    let current = true
    listAvailableAgents(spaceId)
      .then((agents) => {
        if (current) setOffered({ read, value: agents.some((agent) => agent.id === agentId) })
      })
      .catch(() => {
        if (current) setOffered({ read, value: false })
      })
    return () => {
      current = false
    }
  }, [agentsOn, spaceId, agentId, read])

  return agentsOn && offered?.read === read && offered.value
}
