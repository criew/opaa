package io.opaa.group.sync;

import java.util.UUID;

/**
 * Default {@link DirectoryClient} used whenever no real directory connection is configured (see
 * {@link DirectorySyncConfiguration}). Always reports the directory as unreachable rather than,
 * say, returning an empty group list - "no directory configured" and "directory unreachable"
 * require the identical safe response (last-known-good, nothing revoked, see #237), so there is no
 * reason to invent a third state for this case.
 */
public class NoOpDirectoryClient implements DirectoryClient {

  @Override
  public DirectorySnapshot fetchGroups(UUID organizationId) throws DirectoryUnavailableException {
    throw new DirectoryUnavailableException(
        "No directory client is configured for this deployment");
  }
}
