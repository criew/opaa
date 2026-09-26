package io.opaa.permission;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The membership questions {@link GroupMembershipResolver} asks of whoever stores group memberships
 * - the port that keeps this package free of a dependency on {@code io.opaa.group} (ADR-0036,
 * Entscheidung 12). Implemented by {@code GroupMembershipRepository}: the permission model asks,
 * the group administration answers.
 */
public interface GroupMembershipSource {

  /** The ids of the groups {@code userId} is a direct member of. */
  Set<UUID> findGroupIdsByUserId(UUID userId);

  /**
   * The members of a group, scoped to the given organization - so a subject carrying the wrong
   * {@code organizationId} (whether by bug or by a crafted request) resolves to nobody instead of
   * leaking members across the organization boundary. There is no unscoped equivalent on purpose: a
   * caller that skips the organization would reintroduce exactly the cross-tenant leak #199 closed.
   */
  Set<UUID> findUserIdsByGroupIdAndOrganizationId(UUID groupId, UUID organizationId);

  /**
   * How many of those members are active accounts at {@code now} - counted in the database, so the
   * figure beside every group row costs no member list. Same organization scoping and same
   * definition of "active" as {@code AccountActivityService}, which the parity test holds to.
   */
  int countActiveMembers(UUID groupId, UUID organizationId, Instant now);

  /**
   * One page of those active accounts, ordered by name (#1880). The cap is the {@code LIMIT} of the
   * query itself rather than a filter applied afterwards: the one caller that reads members by name
   * - the grant giver of ADR-0036, Entscheidung 9 - must not be able to pull a group of five
   * thousand into memory. Same organization scoping and same definition of "active" as {@link
   * #countActiveMembers}, whose figure says how far the paging reaches.
   */
  List<UUID> findActiveMemberIdsPage(
      UUID groupId, UUID organizationId, Instant now, int limit, int offset);
}
