/**
 * Session epoch counter (#575): resetAllStores() bumps this once per logout/reset, so an
 * asynchronous store action that captured the epoch *before* the reset can recognize - once its
 * await resolves - that the user's session has since been torn down, and skip the write-back
 * set() call that would otherwise resurrect stale data into a store the reset just emptied.
 *
 * A single shared module-level counter, not per-store state: every resettable store (see
 * resettableStores.ts) checks the same counter rather than each maintaining its own copy of the
 * pattern chatStore's pre-existing chatLoadSequence already used for a narrower purpose (guarding
 * loadChat against a *newer* loadChat/startNewChat call, not against a full session reset).
 *
 * Lives in its own module, not in resettableStores.ts itself, to avoid a circular import: every
 * store that reads this counter would otherwise have to import from resettableStores.ts, which in
 * turn imports every one of those stores to build its registry.
 */
let sessionEpoch = 0

/** The current epoch - callers capture this at the start of an async action, before any await. */
export function currentSessionEpoch(): number {
  return sessionEpoch
}

/** Bumps the epoch, invalidating every previously captured value. Called once by
 * resetAllStores(), never by an individual store. */
export function bumpSessionEpoch(): number {
  sessionEpoch += 1
  return sessionEpoch
}

/** True if `epoch` (captured earlier via currentSessionEpoch()) no longer matches the current
 * one, i.e. resetAllStores() ran at least once in between. */
export function isStaleSessionEpoch(epoch: number): boolean {
  return epoch !== sessionEpoch
}
