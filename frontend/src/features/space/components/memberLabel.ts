import type { SpaceMemberResponse } from '@/shared/api/types'

/**
 * What a membership is called in list and feedback. A protected group appears in
 * other people's lists without a name; the service delivers none (ADR-0036, Entscheidung 9).
 */
export function memberLabelOf(member: SpaceMemberResponse): string {
  if (member.protectedGroup) return 'Geschützte Gruppe'
  return member.displayName ?? member.subjectId
}

/** How a feedback names the membership: a named group with the word „Gruppe“. */
export function memberNoticeLabelOf(member: SpaceMemberResponse): string {
  const label = memberLabelOf(member)
  return member.subjectType === 'GROUP' && !member.protectedGroup ? `Gruppe ${label}` : label
}
