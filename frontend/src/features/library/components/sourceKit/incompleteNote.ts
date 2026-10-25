import { plainMessage } from './plainMessage'
import type { BrowseResult } from './selection'

/**
 * The line above a listing that is not complete, null for a complete one. With entries the list
 * was cut („Liste unvollständig.“ and the reason); without entries the source refused, and its
 * reason alone says why nothing is listed.
 */
export function incompleteNote(result: BrowseResult): string | null {
  if (result.complete) return null
  const reason = result.message ? plainMessage(result.message) : ''
  if (result.entries.length === 0 && reason) return reason
  return reason ? `Liste unvollständig. ${reason}` : 'Liste unvollständig.'
}
