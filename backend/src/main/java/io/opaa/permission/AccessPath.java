package io.opaa.permission;

import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.SpaceRole;
import java.time.Instant;
import java.util.Objects;

/**
 * One way a person reaches one object - the element of the Herleitung (ADR-0036, Entscheidung 9).
 * Exactly one of {@code assetRole}/{@code spaceRole} is set: the two rights axes name their roles
 * differently, and folding them into one string would cost every consumer the enum.
 *
 * @param since when the way came into being (grant, admission); null where it has none.
 * @param group the group the way runs through; null for every other basis.
 */
public record AccessPath(
    AccessBasis basis,
    AssetRole assetRole,
    SpaceRole spaceRole,
    Instant since,
    GroupAttribution group) {

  public AccessPath {
    Objects.requireNonNull(basis, "basis must not be null");
  }

  public static AccessPath ofAsset(
      AccessBasis basis, AssetRole role, Instant since, GroupAttribution group) {
    return new AccessPath(basis, role, null, since, group);
  }

  public static AccessPath ofSpace(
      AccessBasis basis, SpaceRole role, Instant since, GroupAttribution group) {
    return new AccessPath(basis, null, role, since, group);
  }
}
