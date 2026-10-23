package io.opaa.compliance;

import java.util.Objects;
import java.util.UUID;

/**
 * The one input of a {@link SelfDisclosureExporter}: the signed-in person herself, never a
 * parameter of the request.
 *
 * @param accountId the {@code users.id} of the person
 * @param organizationId her organization
 */
public record SelfDisclosureSubject(UUID accountId, UUID organizationId) {

  public SelfDisclosureSubject {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(organizationId, "organizationId");
  }
}
