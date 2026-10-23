package io.opaa.api.types;

/**
 * Where the deletion of the chats of a local account after a lock by the administration stands:
 * switched off, not started because the account is not locked by the administration, scheduled, or
 * held for the account. A lock for inactivity or after failed sign-ins is {@link #NOT_LOCKED}.
 */
public enum ChatCleanupAfterLockStatus {
  SWITCHED_OFF,
  NOT_LOCKED,
  SCHEDULED,
  HELD
}
