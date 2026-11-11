import { describe, expect, it } from 'vitest'
import type { ChatMessage } from '../types/chat'
import { agentFollowUp } from './agentFollowUp'

function answer(endState: 'COMPLETED' | 'ASK_USER' | 'PLAN', agentId: string | null = 'a-1') {
  return {
    id: `m-${endState}`,
    role: 'assistant',
    content: 'Antwort',
    timestamp: new Date(0),
    agentRun: { runId: 'r-1', endState, steps: [], agentId, agentName: 'Recherche' },
  } as unknown as ChatMessage
}

const question = { id: 'q', role: 'user', content: 'Frage', timestamp: new Date(0) } as ChatMessage

describe('agentFollowUp', () => {
  it('names the agent of a latest plan or question back', () => {
    expect(agentFollowUp([question, answer('PLAN')])).toEqual({
      key: 'm-PLAN',
      agent: { id: 'a-1', name: 'Recherche' },
      plan: true,
    })
    expect(agentFollowUp([question, answer('ASK_USER')])).toMatchObject({ plan: false })
  })

  it('is null for a finished run, an older plan, an unnamed agent or no messages', () => {
    expect(agentFollowUp([question, answer('COMPLETED')])).toBeNull()
    expect(agentFollowUp([answer('PLAN'), question])).toBeNull()
    expect(agentFollowUp([question, answer('PLAN', null)])).toBeNull()
    expect(agentFollowUp([])).toBeNull()
  })
})
