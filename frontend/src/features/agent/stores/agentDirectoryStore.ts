import { create } from 'zustand'
import type { AgentDirectoryResponse } from '@/shared/api/types'
import { getAgentDirectory } from '../api/agentApi'
import { currentSessionEpoch, isStaleSessionEpoch } from '@/shared/session/sessionEpoch'
import { registerSessionStore } from '@/shared/session/resettableStores'

/** Why the directory is not shown: no right, switched off, or a failure to load. */
export type AgentDirectoryProblem = 'FORBIDDEN' | 'UNAVAILABLE' | 'FAILED'

interface AgentDirectoryState {
  directory: AgentDirectoryResponse | null
  problem: AgentDirectoryProblem | null
  error: string | null
  loading: boolean
  reset: () => void
  /** Reads the directory; every read is an entry in the audit log, so a page asks once. */
  load: () => Promise<void>
}

function statusOf(err: unknown): number | undefined {
  const cause = err instanceof Error ? (err.cause as { response?: { status?: number } }) : undefined
  return cause?.response?.status
}

/**
 * The agent directory of this session. The list and the detail pages share one read: opening an
 * entry shows what the list already holds instead of reading - and auditing - the directory again.
 */
export const useAgentDirectoryStore = create<AgentDirectoryState>((set) => ({
  directory: null,
  problem: null,
  error: null,
  loading: false,

  reset: () => set({ directory: null, problem: null, error: null, loading: false }),

  load: async () => {
    const sessionEpoch = currentSessionEpoch()
    set({ loading: true })
    try {
      const directory = await getAgentDirectory()
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ directory, problem: null, error: null, loading: false })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const status = statusOf(err)
      set({
        directory: null,
        problem: status === 403 ? 'FORBIDDEN' : status === 404 ? 'UNAVAILABLE' : 'FAILED',
        error: err instanceof Error ? err.message : null,
        loading: false,
      })
    }
  },
}))

registerSessionStore(useAgentDirectoryStore)
