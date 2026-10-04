import { confirmAction } from '../../../stores/confirmStore'

/**
 * Die Rückfrage vor dem Sperren oder Entsperren einer Quellart oder eines Zugangs; `target` ist
 * der Gegenstand im Akkusativ („die Quellart „Nextcloud““, „den Zugang „Nextcloud intern““).
 * `remainingLock` nennt beim Entsperren eine Sperre, die bestehen bleibt - dann verspricht die
 * Rückfrage keinen Weiterlauf.
 */
export function confirmLock(
  target: string,
  lock: boolean,
  remainingLock?: string,
): Promise<boolean> {
  return lock
    ? confirmAction({
        question: `${capitalize(target)} sperren?`,
        consequence:
          'Neue Bibliotheken sind dann nicht mehr möglich, auch nicht für die Systemverwaltung. Die Bibliotheken laufen nicht mehr; ein Lauf, der gerade läuft, endet regulär. Der vorhandene Inhalt bleibt durchsuchbar und trägt den Hinweis „Gesperrt“. Die Sperre wird als Governance-Ereignis protokolliert.',
        confirmLabel: 'Sperren',
        tone: 'danger',
      })
    : confirmAction({
        question: `Sperre für ${target} aufheben?`,
        consequence: remainingLock
          ? `${remainingLock} Das Aufheben wird als Governance-Ereignis protokolliert.`
          : 'Die Bibliotheken laufen ohne Neueinrichtung weiter, und neue sind wieder nach Freigabe möglich. Das Aufheben wird als Governance-Ereignis protokolliert.',
        confirmLabel: 'Entsperren',
      })
}

function capitalize(text: string) {
  return text.charAt(0).toUpperCase() + text.slice(1)
}
