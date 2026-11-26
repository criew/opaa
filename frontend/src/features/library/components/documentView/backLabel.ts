import type { ChunkAnchor } from '@/shared/api/types'

/**
 * The way back out of the reading mode: a view opened from a citation (with an anchor, even a
 * missing one) returns to the answer, one opened from the library just closes.
 */
export function backLabel(anchor: ChunkAnchor | null | undefined): string {
  return anchor === undefined ? 'Zurück' : 'Zurück zur Antwort'
}
