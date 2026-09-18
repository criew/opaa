package io.opaa.search;

import java.util.Set;
import java.util.UUID;

/**
 * What one request may see and under which quota it counts.
 *
 * @param libraryIds the libraries of the effective view, already narrowed by every applicable
 *     factor.
 * @param accessTokenId the access token this request arrived on, or {@code null} for a signed-in
 *     person. It is the key of the per-token quota and the only identifier the mass-retrieval alert
 *     names; a person has neither, which is what keeps both free of any per-person evaluation.
 */
public record SearchRequestScope(Set<UUID> libraryIds, UUID accessTokenId) {

  public SearchRequestScope {
    libraryIds = libraryIds == null ? Set.of() : Set.copyOf(libraryIds);
  }

  public static SearchRequestScope ofPerson(Set<UUID> libraryIds) {
    return new SearchRequestScope(libraryIds, null);
  }
}
