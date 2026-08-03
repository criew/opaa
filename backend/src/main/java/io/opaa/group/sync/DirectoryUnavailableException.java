package io.opaa.group.sync;

/**
 * Thrown by {@link DirectoryClient#fetchGroups} when the directory cannot be reached at all
 * (connection failure, timeout, authentication failure against the directory itself). {@link
 * DirectorySyncService} treats this as last-known-good: nothing is changed, the previous
 * synchronisation's result stays in force, and the unreachable state is reported rather than
 * treated as "zero groups" (see #237's acceptance criteria - an unreachable directory must never be
 * able to revoke rights).
 */
public class DirectoryUnavailableException extends Exception {

  public DirectoryUnavailableException(String message) {
    super(message);
  }

  public DirectoryUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
