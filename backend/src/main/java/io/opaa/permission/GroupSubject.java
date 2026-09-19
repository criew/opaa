package io.opaa.permission;

import java.util.Objects;
import java.util.UUID;

/**
 * A group seen through {@link GroupSubjectDirectory}: exactly the three properties a grant path
 * decides on - the organization it belongs to, the name a response shows, and whether it was
 * dissolved (a dissolved group keeps its existing grants but may not receive a new or updated one,
 * see docs/features/spaces-and-assets.md#reorganisation-umbenennung-zusammenlegung).
 */
public record GroupSubject(UUID id, UUID organizationId, String name, boolean dissolved) {

  public GroupSubject {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(organizationId, "organizationId must not be null");
  }
}
