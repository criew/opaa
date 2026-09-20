package io.opaa.permission;

import java.util.Objects;
import java.util.UUID;

/**
 * A group seen through {@link GroupSubjectDirectory}: exactly the properties a grant path decides
 * on - the organization it belongs to, the name a response shows, and the three reasons it may be
 * no effective group. A dissolved group keeps its existing grants but may not receive a new or
 * updated one (docs/features/spaces-and-assets.md#reorganisation-umbenennung-zusammenlegung); the
 * same holds for a group whose identity provider is switched off (ADR-0036, Entscheidung 2) -
 * otherwise a release to it would reach nobody and then, with the provider switched back on,
 * everybody at once without a second decision.
 *
 * <p>{@code unmaintained} is the third: a token group of a provider that has since switched to the
 * directory run (#1816, ADR-0036 Entscheidung 3). Its membership is frozen - the change of
 * mechanism deliberately revokes nothing - but frozen membership is exactly why it must not become
 * a new grant target: nobody would ever join or leave it again.
 */
public record GroupSubject(
    UUID id,
    UUID organizationId,
    String name,
    boolean dissolved,
    boolean providerDisabled,
    boolean unmaintained) {

  public GroupSubject {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(organizationId, "organizationId must not be null");
  }
}
