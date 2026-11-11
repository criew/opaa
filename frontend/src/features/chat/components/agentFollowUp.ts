import type { ChatMessage } from '../types/chat'

/** The agent a question is sent to; it replaces the question's search scope. */
export interface ChosenAgent {
  id: string
  name: string
}

/** What the confirmation of a plan sends as the person's message; it starts the working run. */
export const PLAN_CONFIRMATION_TEXT = 'Plan bestätigt.'

/** An agent the chat's latest answer waits on, with the answer's id as key. */
export interface AgentFollowUp {
  key: string
  agent: ChosenAgent
  /** True for a plan to confirm or change, false for a question back. */
  plan: boolean
}

/**
 * The agent whose run ended the chat with a question back or a plan: the person's next message
 * goes to it, since its reply starts the agent's next run. Null when the latest message is no such
 * answer or names no agent.
 */
export function agentFollowUp(messages: readonly ChatMessage[]): AgentFollowUp | null {
  const latest = messages[messages.length - 1]
  const run = latest?.role === 'assistant' ? latest.agentRun : undefined
  if (!run?.agentId || (run.endState !== 'PLAN' && run.endState !== 'ASK_USER')) return null
  return {
    key: latest.id,
    agent: { id: run.agentId, name: run.agentName ?? 'Agent' },
    plan: run.endState === 'PLAN',
  }
}
