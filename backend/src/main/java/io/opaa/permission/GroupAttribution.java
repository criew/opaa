package io.opaa.permission;

import io.opaa.api.types.GroupMechanism;
import io.opaa.api.types.GroupOrigin;
import java.util.Objects;
import java.util.UUID;

/**
 * A group as the Herleitung names it (ADR-0036, Entscheidung 9): name, origin, the provider it
 * comes from and the mechanism that maintains its membership - the mechanism because the accuracy
 * of the Rechtehistorie depends on it (Entscheidung 3). {@code protectedGroup} is not shown, it
 * decides: a way through a protected group is never named to a third party.
 */
public record GroupAttribution(
    UUID id,
    String name,
    GroupOrigin origin,
    String providerName,
    GroupMechanism mechanism,
    boolean protectedGroup) {

  public GroupAttribution {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(origin, "origin must not be null");
    Objects.requireNonNull(mechanism, "mechanism must not be null");
  }
}
