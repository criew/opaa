import { notify } from '../../../stores/notificationStore'

/**
 * Kopiert einen Wert, der nur einmal angezeigt wird, und sagt in beiden Fällen, was geschehen ist:
 * Ein stilles Scheitern wäre hier ein Datenverlust – der Link oder das Passwort ist danach nicht
 * wieder abrufbar, und wer ein leeres Clipboard einfügt, merkt es erst beim Gegenüber.
 */
export async function copyOnce(value: string, what: string): Promise<void> {
  try {
    if (!navigator.clipboard) throw new Error('Clipboard API unavailable')
    await navigator.clipboard.writeText(value)
    notify(`${what} wurde kopiert.`, 'success')
  } catch {
    notify(`${what} konnte nicht kopiert werden – bitte manuell markieren.`, 'error')
  }
}
