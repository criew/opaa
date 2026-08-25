package io.opaa.common;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Loads an organization-scoped entity and treats one that belongs to a different organization as
 * not found rather than forbidden - the organization boundary is never revealed, not even to a
 * system administrator. Centralizes the {@code loadSpace}/{@code loadGroup}/{@code
 * requireUserInOrganization} pattern previously copied with identical shape across {@code
 * SpaceService}, {@code SpaceAssetAssociationService} and {@code GroupService} (#888).
 */
public final class OrganizationScopedLoader {

  private OrganizationScopedLoader() {}

  public static <T> T load(
      Supplier<Optional<T>> finder,
      Function<T, UUID> organizationIdOf,
      UUID callerOrganizationId,
      String notFoundMessage) {
    T entity = finder.get().orElseThrow(() -> new NotFoundException(notFoundMessage));
    if (!organizationIdOf.apply(entity).equals(callerOrganizationId)) {
      throw new NotFoundException(notFoundMessage);
    }
    return entity;
  }
}
