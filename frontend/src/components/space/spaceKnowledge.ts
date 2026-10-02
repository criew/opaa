import type { SpaceRole } from '../../types/api'

/** Why a space's chat searches nothing: nothing associated, or nothing of it readable. */
export type SpaceKnowledgeGap = 'none-assigned' | 'none-readable'

export const NO_KNOWLEDGE_ASSIGNED = 'Diesem Space ist kein Wissen zugeordnet.'
export const NO_KNOWLEDGE_READABLE = 'In diesem Space ist für Sie derzeit kein Wissen verfügbar.'

/** Who may associate knowledge: a CURATOR or ADMIN of the space. */
export function canAssignKnowledge(role: SpaceRole | string | undefined): boolean {
  return role === 'CURATOR' || role === 'ADMIN'
}

/**
 * The gap of a space whose associations are known, or `null` when a chat there searches
 * something. Both signals come from the server and carry no count.
 */
export function spaceKnowledgeGap(
  hasKnowledge: boolean,
  hasReadableKnowledge: boolean,
): SpaceKnowledgeGap | null {
  if (!hasKnowledge) return 'none-assigned'
  if (!hasReadableKnowledge) return 'none-readable'
  return null
}
