import type { AgentResponse, AgentUpdateRequest } from '@/shared/api/types'

/** The whole update request of the agent as saved, with one area's change on top. */
export function updateRequest(
  agent: AgentResponse,
  change: Partial<AgentUpdateRequest>,
): AgentUpdateRequest {
  return {
    name: agent.name,
    description: agent.description ?? null,
    instruction: agent.instruction,
    // Hidden libraries are not sent: the backend keeps those the editing person cannot read.
    libraryIds: agent.libraries.map((library) => library.id),
    toolNames: agent.toolNames,
    planStep: agent.planStep,
    limits: agent.limits,
    ...change,
  }
}
