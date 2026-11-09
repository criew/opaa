import { useEffect, useState } from 'react'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import { getAgents } from '../api/agentApi'
import type { AgentResponse } from '../types'

/** The agents the person may call in the chat; none while the feature is off. */
export function useCallableAgents(): AgentResponse[] {
  const enabled = useFeatureEnabled('agents')
  const [agents, setAgents] = useState<AgentResponse[]>([])
  useEffect(() => {
    if (!enabled) return
    void getAgents()
      .then((list) => setAgents(list.filter((agent) => !agent.locked)))
      .catch(() => setAgents([]))
  }, [enabled])
  return enabled ? agents : []
}
