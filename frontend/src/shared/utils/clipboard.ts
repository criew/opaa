import { notify } from '../stores/notificationStore'

/**
 * Copies a value and says so (guidelines 5.9). Every failure path - no Clipboard API, a denied
 * permission, a document without focus - ends in the same sentence with a way out by hand, never
 * in a silent no-op: a person who believes they copied a generated password and did not would lose
 * it.
 */
export async function copyToClipboard(value: string, what: string): Promise<void> {
  try {
    if (!navigator.clipboard) throw new Error('Clipboard API unavailable')
    await navigator.clipboard.writeText(value)
    notify(`${what} kopiert.`, 'success')
  } catch {
    notify(`${what} konnte nicht kopiert werden – bitte manuell markieren.`, 'error')
  }
}
