import type { CapabilityOverviewResponse } from '@/shared/api/types'
import { grantCapability, revokeCapability } from '../api/capabilityAdminApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { confirmGroupSubject } from '@/features/principal'
import { planAccessChange, resultSentence, type Access } from './capabilityAccess'

/**
 * The questions before a change is sent: the confirmation before all accounts lose the right and
 * the one before a group of an external provider gets it.
 */
export async function confirmAccess(
  entry: CapabilityOverviewResponse,
  target: Access,
  phrase: string,
): Promise<boolean> {
  const plan = planAccessChange(entry, target)
  if (plan.withdrawsAllAccounts) {
    const confirmed = await confirmAction({
      question: 'Nicht mehr für alle Konten?',
      consequence: `${resultSentence(target, phrase)} Für alle anderen Konten gilt das sofort, auch für Personen, die gerade angemeldet sind. Die Änderung wird protokolliert.`,
      confirmLabel: 'Einschränken',
      tone: 'danger',
    })
    if (!confirmed) return false
  }
  for (const subject of target.subjects) {
    const added = plan.steps.some((step) => step.kind === 'GRANT' && step.subjectId === subject.id)
    if (added && subject.group && !(await confirmGroupSubject(subject.group))) return false
  }
  return true
}

/** Grants before revocations; a failure midway names how far it got. */
export async function applyAccess(
  entry: CapabilityOverviewResponse,
  target: Access,
): Promise<void> {
  const plan = planAccessChange(entry, target)
  let done = 0
  try {
    for (const step of plan.steps) {
      if (step.kind === 'GRANT') {
        await grantCapability(entry.capability, {
          subjectType: step.subjectType,
          ...(step.subjectId ? { subjectId: step.subjectId } : {}),
          ...(entry.scope ? { scope: entry.scope } : {}),
        })
      } else {
        await revokeCapability(entry.capability, step.grantId)
      }
      done += 1
    }
  } catch (err) {
    const reason =
      err instanceof Error ? err.message : 'Die Änderung konnte nicht gespeichert werden.'
    throw new Error(
      done === 0
        ? reason
        : `${reason} Ausgeführt wurden ${done} von ${plan.steps.length} Schritten; die Übersicht zeigt den Stand danach.`,
      { cause: err },
    )
  }
}
