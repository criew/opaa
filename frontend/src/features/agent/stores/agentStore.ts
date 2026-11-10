import { create } from 'zustand'
import type { AgentCreateRequest, AgentResponse, AgentUpdateRequest } from '@/shared/api/types'
import { createAgent, deleteAgent, getAgent, getAgents, updateAgent } from '../api/agentApi'
import { currentSessionEpoch, isStaleSessionEpoch } from '@/shared/session/sessionEpoch'
import { registerSessionStore } from '@/shared/session/resettableStores'

interface AgentState {
  agents: AgentResponse[]
  details: Record<string, AgentResponse>
  error: string | null
  reset: () => void
  loadAgents: () => Promise<void>
  loadAgent: (agentId: string) => Promise<void>
  createAgent: (request: AgentCreateRequest) => Promise<string>
  updateAgent: (agentId: string, request: AgentUpdateRequest) => Promise<AgentResponse>
  deleteAgent: (agentId: string) => Promise<void>
}

/** The agents of this session; every write keeps the detail and the list in step with the server. */
export const useAgentStore = create<AgentState>((set, get) => ({
  agents: [],
  details: {},
  error: null,

  reset: () => set({ agents: [], details: {}, error: null }),

  loadAgents: async () => {
    const sessionEpoch = currentSessionEpoch()
    try {
      const agents = await getAgents()
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ agents })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ error: err instanceof Error ? err.message : 'Agenten konnten nicht geladen werden' })
    }
  },

  loadAgent: async (agentId) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      const agent = await getAgent(agentId)
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ details: { ...get().details, [agentId]: agent }, error: null })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ error: err instanceof Error ? err.message : 'Agent konnte nicht geladen werden' })
    }
  },

  createAgent: async (request) => {
    const sessionEpoch = currentSessionEpoch()
    const agent = await createAgent(request)
    if (!isStaleSessionEpoch(sessionEpoch)) {
      set({ details: { ...get().details, [agent.id]: agent } })
    }
    return agent.id
  },

  updateAgent: async (agentId, request) => {
    const sessionEpoch = currentSessionEpoch()
    const agent = await updateAgent(agentId, request)
    if (!isStaleSessionEpoch(sessionEpoch)) {
      set({ details: { ...get().details, [agentId]: agent } })
    }
    return agent
  },

  deleteAgent: async (agentId) => {
    const sessionEpoch = currentSessionEpoch()
    await deleteAgent(agentId)
    if (isStaleSessionEpoch(sessionEpoch)) return
    const details = { ...get().details }
    delete details[agentId]
    set({ details, agents: get().agents.filter((agent) => agent.id !== agentId) })
  },
}))

registerSessionStore(useAgentStore)
