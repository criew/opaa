package io.opaa.group.sync;

import java.util.Objects;
import java.util.Set;

/**
 * One group as reported by the directory for a single synchronisation snapshot.
 *
 * @param externalId the directory's stable identifier for this group (objectGUID or SCIM {@code
 *     externalId}) - what {@link DirectorySyncService} matches on instead of {@code name}, so a
 *     rename in the directory never orphans a grant (see #237 and {@code io.opaa.group.Group}).
 * @param name the group's current display name in the directory
 * @param parentExternalId the parent organizational unit's {@code externalId}, or {@code null} at
 *     the top of the hierarchy. Recorded on {@code Group.parentGroupId} for #208's curator
 *     escalation only - {@link DirectorySyncService} deliberately does not resolve membership
 *     transitively through it. A member of a child unit is not implicitly a member of its parent;
 *     each group's membership is exactly what the directory reports as direct members of that
 *     group. Nested-group membership inheritance is an open point in the feature spec and is
 *     intentionally out of scope here rather than left unresolved silently.
 * @param memberSubjects the OIDC {@code subject} (not the directory's own member identifier) of
 *     every direct member, matched against {@code User.subject} scoped to the organization (see
 *     {@code UserRepository#findByOrganizationIdAndSubjectIn}). A subject with no matching user
 *     (not yet provisioned, or provisioned under a different issuer) is skipped rather than treated
 *     as an error - SCIM user provisioning is out of scope for #237.
 */
public record DirectoryGroup(
    String externalId, String name, String parentExternalId, Set<String> memberSubjects) {

  public DirectoryGroup {
    Objects.requireNonNull(externalId, "externalId must not be null");
    Objects.requireNonNull(name, "name must not be null");
    memberSubjects = memberSubjects == null ? Set.of() : Set.copyOf(memberSubjects);
  }
}
