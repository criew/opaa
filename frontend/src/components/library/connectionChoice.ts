import type {
  ConnectionProfileOption,
  SourceBlockReason,
  SourceTypeDescriptor,
} from '../../types/api'
import { ownAddressAllowed } from './sources/sourceConnection'

/** The choice of a library with its own address instead of a profile. */
export const OWN_ADDRESS = 'own-address'

/** The keys that may be chosen: the own address where offered and admitted, every usable profile. */
export function selectableConnections(
  descriptor: SourceTypeDescriptor,
  options: ConnectionProfileOption[],
  offerOwnAddress: boolean,
): string[] {
  return [
    ...(offerOwnAddress && ownAddressAllowed(descriptor) ? [OWN_ADDRESS] : []),
    ...options.filter((option) => option.creatable).map((option) => option.id),
  ]
}

/**
 * The choice in effect: `chosen` while it may be chosen, otherwise the first that may - so a
 * choice the answer turned unusable never stays selected. `null` when there is none at all.
 */
export function effectiveConnection(chosen: string | null, selectable: string[]): string | null {
  if (chosen !== null && selectable.includes(chosen)) return chosen
  return selectable[0] ?? null
}

/** Whether connecting the library through a profile lifts this block - then it is offered there. */
export function liftedByConnecting(reason: SourceBlockReason): boolean {
  switch (reason) {
    case 'PROFILE_REQUIRED':
    case 'ACCESS_REMOVED':
      return true
    case 'TYPE_LOCKED':
    case 'PROFILE_LOCKED':
    case 'OWNER_DEACTIVATED':
    case 'DORMANT':
    case 'TARGET_OUTSIDE_PROFILE':
    case 'NOT_CONNECTED':
    case 'EXPIRED':
      return false
    default: {
      const unknown: never = reason
      throw new Error(`Unknown source block reason ${String(unknown)}`)
    }
  }
}
