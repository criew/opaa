package io.opaa.group.sync;

import java.util.UUID;

/**
 * The single extension point between {@link DirectorySyncService}'s synchronisation policy and an
 * actual directory (LDAP, Entra ID, the Keycloak Admin API, ...). {@link DirectorySyncService} is
 * tested exhaustively against a test double of this interface; wiring a real directory is a
 * deployment-specific concern (protocol, credentials, network reachability) and arrives with #1817.
 * The codebase ships {@link NoOpDirectoryClient} as the default bean so the application boots and
 * behaves safely - permanently "unreachable", never a false "zero groups" - until an operator
 * provides a real implementation.
 */
public interface DirectoryClient {

  /**
   * Reads the current, complete group list of <b>one identity provider's</b> directory. Since #1816
   * the run is bound to a provider row rather than to the organization alone, so an implementation
   * is told which directory to read - an installation with two providers has two directories, and
   * an organization-wide call could not tell them apart.
   *
   * @throws DirectoryUnavailableException if the directory cannot be reached or answered with an
   *     error - never thrown for "the directory has no groups", which is a valid, empty {@link
   *     DirectorySnapshot}.
   */
  DirectorySnapshot fetchGroups(UUID organizationId, UUID providerId)
      throws DirectoryUnavailableException;
}
