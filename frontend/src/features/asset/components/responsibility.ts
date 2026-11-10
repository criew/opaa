import { formatDate } from '@/shared/utils/formatDate'
import type {
  AssetResponsibilityResponse,
  GroupListResponse,
  ResponsibleUnitRequest,
} from '@/shared/api/types'

/** The field's state: a chosen group, or the text typed as a unit or function. */
export interface ResponsibleDraft {
  group: { id: string; name: string } | null
  text: string
}

export const EMPTY_RESPONSIBLE: ResponsibleDraft = { group: null, text: '' }

/** The draft for a group, e.g. the first group of the creating person. */
export function responsibleDraftOfGroup(group: GroupListResponse | undefined): ResponsibleDraft {
  return group ? { group: { id: group.id, name: group.name }, text: group.name } : EMPTY_RESPONSIBLE
}

/** The request a draft sends: the group, else the trimmed text, else nothing. */
export function responsibleRequest(draft: ResponsibleDraft): ResponsibleUnitRequest | null {
  if (draft.group) return { groupId: draft.group.id, function: null }
  const text = draft.text.trim()
  return text === '' ? null : { groupId: null, function: text }
}

export const RESPONSIBLE_REQUIRED =
  'Geben Sie die verantwortliche Stelle an: eine Gruppe, Organisationseinheit oder Funktion.'

/** The unit as one line: the group's or the function's name, or that none is named. */
export function responsibleLabel(responsibility: AssetResponsibilityResponse): string {
  return responsibility.groupName ?? responsibility.function ?? 'Keine verantwortliche Stelle'
}

/** The note on a missing unit or a lock, for the head of a detail page; null while all is well. */
export function responsibilityNote(
  responsibility: AssetResponsibilityResponse,
): { severity: 'warning' | 'error'; text: string } | null {
  const lock = responsibility.lock
  if (lock) {
    return {
      severity: 'error',
      text:
        lock.cause === 'NO_RESPONSIBLE'
          ? `Gesperrt seit ${formatDate(lock.lockedAt)}, weil die verantwortliche Stelle fehlt. Nach dem Benennen einer Stelle kann die Systemverwaltung die Sperre aufheben.`
          : `Gesperrt seit ${formatDate(lock.lockedAt)} durch die Systemverwaltung: ${lock.reason}`,
    }
  }
  if (responsibility.missing) {
    return {
      severity: 'warning',
      text: responsibility.lockDueAt
        ? `Die verantwortliche Stelle fehlt. Ohne neue Stelle wird am ${formatDate(responsibility.lockDueAt)} gesperrt.`
        : 'Die verantwortliche Stelle fehlt. Ohne neue Stelle wird nach Ablauf der Frist gesperrt.',
    }
  }
  return null
}
