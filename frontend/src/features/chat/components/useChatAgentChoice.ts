import { useEffect, useState } from 'react'
import type { AvailableAgent } from '@/shared/api/types'
import { useRuntimeSwitchStore } from '@/shared/session/runtimeSwitches'
import { listAvailableAgents } from '@/features/agent'

/**
 * The agents the person can choose with '@' in a chat of the space, and the one chosen for the
 * next question. A chosen agent applies to that question only and replaces its search scope; while
 * agents are switched off nothing is offered. A failed load offers no agent rather than an error.
 */
export function useChatAgentChoice(spaceId: string | null) {
  const agentsOn = useRuntimeSwitchStore((state) => state.agents)
  const [loaded, setLoaded] = useState<{ spaceId: string; agents: AvailableAgent[] } | null>(null)
  const [chosen, setChosen] = useState<{ spaceId: string; agent: AvailableAgent } | null>(null)

  useEffect(() => {
    if (!agentsOn || !spaceId) return
    let current = true
    listAvailableAgents(spaceId)
      .then((agents) => {
        if (current) setLoaded({ spaceId, agents })
      })
      .catch(() => {
        if (current) setLoaded({ spaceId, agents: [] })
      })
    return () => {
      current = false
    }
  }, [agentsOn, spaceId])

  const agents = agentsOn && loaded && loaded.spaceId === spaceId ? loaded.agents : []
  const agent =
    agentsOn && chosen && chosen.spaceId === spaceId
      ? (agents.find((offered) => offered.id === chosen.agent.id) ?? chosen.agent)
      : null

  return {
    agents,
    agent,
    chooseAgent: (next: AvailableAgent) => {
      if (spaceId) setChosen({ spaceId, agent: next })
    },
    clearAgent: () => setChosen(null),
  }
}
