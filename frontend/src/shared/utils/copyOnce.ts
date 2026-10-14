import { notify } from '../stores/notificationStore'

/**
 * Copies a value that is shown only once and says in both cases what happened:
 * a silent failure would be a data loss here – the link or the password cannot be retrieved
 * again afterwards, and whoever pastes an empty clipboard only notices at the other end.
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
