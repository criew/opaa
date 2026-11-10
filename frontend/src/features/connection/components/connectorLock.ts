import { confirmAction } from '@/shared/stores/confirmStore'

/**
 * The confirmation before locking or unlocking a source type or a connection profile, asked by its
 * name alone (guidelines 5.10); the row it is opened from says which of the two it is.
 * When unlocking, `remainingLock` names a lock that stays in place - then the
 * confirmation promises no resumption.
 */
export function confirmLock(name: string, lock: boolean, remainingLock?: string): Promise<boolean> {
  return lock
    ? confirmAction({
        question: `„${name}“ sperren?`,
        consequence:
          'Neue Bibliotheken sind dann nicht mehr möglich, auch nicht für die Systemverwaltung. Die Bibliotheken laufen nicht mehr; ein Lauf, der gerade läuft, endet regulär. Der vorhandene Inhalt bleibt durchsuchbar und trägt den Hinweis „Gesperrt“. Die Sperre wird als Governance-Ereignis protokolliert.',
        confirmLabel: 'Sperren',
        tone: 'danger',
      })
    : confirmAction({
        question: `„${name}“ entsperren?`,
        consequence: remainingLock
          ? `${remainingLock} Das Aufheben wird als Governance-Ereignis protokolliert.`
          : 'Die Bibliotheken laufen ohne Neueinrichtung weiter, und neue sind wieder nach Freigabe möglich. Das Aufheben wird als Governance-Ereignis protokolliert.',
        confirmLabel: 'Entsperren',
      })
}
