import type { SpaceMemberResponse } from '../../types/api'

/**
 * Wie eine Mitgliedschaft in Liste und Rückmeldung heißt. Eine geschützte Gruppe erscheint in
 * fremden Listen ohne Namen, der Dienst liefert keinen (ADR-0036, Entscheidung 9).
 */
export function memberLabelOf(member: SpaceMemberResponse): string {
  if (member.protectedGroup) return 'Geschützte Gruppe'
  return member.displayName ?? member.subjectId
}

/** Wie eine Rückmeldung die Mitgliedschaft nennt: eine benannte Gruppe mit dem Wort „Gruppe“. */
export function memberNoticeLabelOf(member: SpaceMemberResponse): string {
  const label = memberLabelOf(member)
  return member.subjectType === 'GROUP' && !member.protectedGroup ? `Gruppe ${label}` : label
}
