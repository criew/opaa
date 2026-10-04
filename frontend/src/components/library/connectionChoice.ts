import type {
  ConnectionProfileOption,
  SourceBlockReason,
  SourceTypeDescriptor,
} from '../../types/api'
import { ownAddressAllowed } from './sources/sourceConnection'

/** The choice of a library with its own address instead of a profile. */
export const OWN_ADDRESS = 'own-address'

const PRIVATE_PREFIX = 'private:'

/** The choice of a private library on the caller's own connected account on `profileId`. */
export function privateConnection(profileId: string): string {
  return `${PRIVATE_PREFIX}${profileId}`
}

/** The profile of a private choice, `null` for every other choice. */
export function privateProfileOf(choice: string | null): string | null {
  return choice?.startsWith(PRIVATE_PREFIX) ? choice.slice(PRIVATE_PREFIX.length) : null
}

/** The profile a choice runs through, private or not; `null` for the own address and none. */
export function profileOfChoice(choice: string | null): string | null {
  if (choice === null || choice === OWN_ADDRESS) return null
  return privateProfileOf(choice) ?? choice
}

/**
 * The keys that may be chosen: the own address where offered and admitted, every usable profile,
 * and - last, so never the default - a private library on every usable profile the caller has a
 * connected account on (`ownAccountProfileIds`).
 */
export function selectableConnections(
  descriptor: SourceTypeDescriptor,
  options: ConnectionProfileOption[],
  offerOwnAddress: boolean,
  ownAccountProfileIds: readonly string[] = [],
): string[] {
  const usable = options.filter((option) => option.creatable)
  return [
    ...(offerOwnAddress && ownAddressAllowed(descriptor) ? [OWN_ADDRESS] : []),
    ...usable.map((option) => option.id),
    ...usable
      .filter((option) => ownAccountProfileIds.includes(option.id))
      .map((option) => privateConnection(option.id)),
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

/** Whether the owner lifts this block on the page "Verbundene Konten" - for a private library. */
export function liftedByOwnAccount(reason: SourceBlockReason): boolean {
  switch (reason) {
    case 'NOT_CONNECTED':
    case 'EXPIRED':
      return true
    case 'TYPE_LOCKED':
    case 'PROFILE_LOCKED':
    case 'PROFILE_REQUIRED':
    case 'ACCESS_REMOVED':
    case 'OWNER_DEACTIVATED':
    case 'DORMANT':
    case 'TARGET_OUTSIDE_PROFILE':
      return false
    default: {
      const unknown: never = reason
      throw new Error(`Unknown source block reason ${String(unknown)}`)
    }
  }
}
