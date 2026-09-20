package io.opaa.permission;

import java.util.Objects;
import java.util.UUID;

/**
 * A group seen through {@link GroupSubjectDirectory}: exactly the properties a grant path decides
 * on - the organization it belongs to, the name a response shows, and the two reasons it may be no
 * effective group. A dissolved group keeps its existing grants but may not receive a new or updated
 * one (docs/features/spaces-and-assets.md#reorganisation-umbenennung-zusammenlegung); the same
 * holds for a group whose identity provider is switched off (ADR-0036, Entscheidung 2) - otherwise
 * a release to it would reach nobody and then, with the provider switched back on, everybody at
 * once without a second decision.
 */
public record GroupSubject(
    UUID id, UUID organizationId, String name, boolean dissolved, boolean providerDisabled) {

  public GroupSubject {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(organizationId, "organizationId must not be null");
  }
}
