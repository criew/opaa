package io.opaa.library;

import java.util.UUID;

/**
 * How many personal access tokens currently carry a library in their selection - the one number the
 * release view of the responsible person shows (docs/features/external-access.md, "Was der
 * Verantwortliche sieht").
 *
 * <p>A number and nothing else: no names, no persons, no usage. A seam rather than a direct call
 * because the tokens live in {@code io.opaa.externalaccess.token}, which already reads this package
 * - the dependency stays one-directional.
 */
public interface LibraryExternalAccessTokenCounter {

  /**
   * Tokens that are neither revoked nor expired and whose selection entry for {@code libraryId} has
   * not been extinguished - that is, exactly those the library currently acts in.
   */
  long countActiveTokensFor(UUID libraryId);
}
