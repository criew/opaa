import type { ConnectionProfileOption, SourceTypeDescriptor } from '../../types/api'
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

/** Whether a shared library may run on the profile: its ownership admits libraries. */
export function admitsLibraries(option: ConnectionProfileOption): boolean {
  return option.ownership !== 'PERSON'
}

/**
 * Whether a private library of the caller may run on the profile: it admits persons and she has a
 * connected account of her own on it.
 */
export function onOwnAccount(option: ConnectionProfileOption): boolean {
  return option.ownAccount && option.ownership !== 'LIBRARY'
}

/** The profiles of `options` that may be chosen now, by id. */
export function profileChoices(options: ConnectionProfileOption[]): string[] {
  return options.filter((option) => option.creatable).map((option) => option.id)
}

/**
 * The keys that may be chosen: the own address where offered and admitted, every usable profile
 * admitting libraries, and - last, so never the default - a private library on every usable
 * profile the caller has a connected account of her own on, a profile only for persons included.
 */
export function selectableConnections(
  descriptor: SourceTypeDescriptor,
  options: ConnectionProfileOption[],
  offerOwnAddress: boolean,
): string[] {
  return [
    ...(offerOwnAddress && ownAddressAllowed(descriptor) ? [OWN_ADDRESS] : []),
    ...profileChoices(options.filter(admitsLibraries)),
    ...profileChoices(options.filter(onOwnAccount)).map(privateConnection),
  ]
}

/**
 * The choice in effect: `chosen` while it may be chosen, otherwise the first shared way that may -
 * so a choice the answer turned unusable never stays selected, and a private library is only ever
 * chosen explicitly. `null` when no shared way is left.
 */
export function effectiveConnection(chosen: string | null, selectable: string[]): string | null {
  if (chosen !== null && selectable.includes(chosen)) return chosen
  return selectable.find((key) => privateProfileOf(key) === null) ?? null
}
