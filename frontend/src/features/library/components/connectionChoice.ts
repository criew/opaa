import type { ConnectionProfileOption, SourceTypeDescriptor } from '@/shared/api/types'
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

/** The profiles of `options` by id; the server lists only those the caller may use now. */
export function profileChoices(options: ConnectionProfileOption[]): string[] {
  return options.map((option) => option.id)
}

/**
 * The keys that may be chosen: every usable profile admitting libraries, then the own address
 * where offered and admitted, and - last, so never the default - a private library on every usable
 * profile the caller has a connected account of her own on, a profile only for persons included.
 */
export function selectableConnections(
  descriptor: SourceTypeDescriptor,
  options: ConnectionProfileOption[],
  offerOwnAddress: boolean,
): string[] {
  return [
    ...profileChoices(options.filter(admitsLibraries)),
    ...(offerOwnAddress && ownAddressAllowed(descriptor) ? [OWN_ADDRESS] : []),
    ...profileChoices(options.filter(onOwnAccount)).map(privateConnection),
  ]
}

/** Whether every sign-in of the type admits only persons: a library of it is always private. */
export function onlyPersons(descriptor: SourceTypeDescriptor): boolean {
  return (
    descriptor.signIns.length > 0 &&
    descriptor.signIns.every((signIn) =>
      signIn.ownerships.every((ownership) => ownership === 'PERSON'),
    )
  )
}

/**
 * The choice in effect: `chosen` while it may be chosen, otherwise the first shared way that may -
 * so a choice the answer turned unusable never stays selected. A private library is chosen
 * explicitly, unless the type admits only persons and it is the one way (`soleWayPrivate`).
 * `null` when nothing is left to take.
 */
export function effectiveConnection(
  chosen: string | null,
  selectable: string[],
  soleWayPrivate = false,
): string | null {
  if (chosen !== null && selectable.includes(chosen)) return chosen
  if (soleWayPrivate && selectable.length === 1) return selectable[0]
  return selectable.find((key) => privateProfileOf(key) === null) ?? null
}
