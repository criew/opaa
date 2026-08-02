package io.opaa.group.sync;

import java.util.UUID;

/**
 * The single extension point between {@link DirectorySyncService}'s synchronisation policy and an
 * actual directory (LDAP, Entra ID, a SCIM server, ...). {@link DirectorySyncService} is tested
 * exhaustively against a test double of this interface; wiring a real directory is a
 * deployment-specific concern (protocol, credentials, network reachability) outside this issue's
 * scope, and the codebase ships {@link NoOpDirectoryClient} as the default bean so the application
 * boots and behaves safely - permanently "unreachable", never a false "zero groups" - until an
 * operator provides a real implementation.
 */
public interface DirectoryClient {

  /**
   * Reads the current, complete group list for an organization from the directory.
   *
   * @throws DirectoryUnavailableException if the directory cannot be reached or answered with an
   *     error - never thrown for "the directory has no groups", which is a valid, empty {@link
   *     DirectorySnapshot}.
   */
  DirectorySnapshot fetchGroups(UUID organizationId) throws DirectoryUnavailableException;
}
