package io.opaa.group;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves which groups a user belongs to, and which users a permission subject (see {@link
 * PermissionSubject}) reaches. This resolution sits on the hot path of every future permission
 * check (readable libraries, agent access - see #202), so it is cached; the cache is invalidated
 * only after the enclosing transaction commits (or rolls back), which {@link GroupService} does on
 * every add, remove and group deletion via its {@code invalidateAfterCommit} helper - not inline,
 * because an inline invalidation can race a concurrent reader into repopulating the cache with the
 * pre-commit state (see {@code GroupService#invalidateAfterCommit} for the sequence).
 *
 * <p>Follows the same direct-Caffeine-cache pattern as {@link io.opaa.api.RateLimitService} rather
 * than the Spring Cache abstraction, to avoid introducing a second caching mechanism into the
 * codebase for a single use case.
 *
 * <p>The cache is process-local. A single instance is all the MVP runs (see {@code
 * docs/MVP-STATUS.md}); horizontal scaling is out of scope for now, but would require either a
 * shared cache or a way to broadcast invalidations, since only the instance that wrote a membership
 * change would otherwise evict it.
 */
@Component
public class GroupMembershipResolver {

  private final GroupMembershipRepository membershipRepository;
  private final Cache<UUID, Set<UUID>> groupIdsByUser;

  public GroupMembershipResolver(GroupMembershipRepository membershipRepository) {
    this.membershipRepository = membershipRepository;
    // A stale entry only ever grants access a moment too long between a completed transaction's
    // invalidation and its next read, never too little - invalidateUser/invalidateUsers below,
    // called post-commit, are the primary correctness mechanism. The time-based expiry is a
    // safety net for eviction paths this class does not yet know about (e.g. a future bulk
    // directory sync, see #237).
    this.groupIdsByUser =
        Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofMinutes(10)).build();
  }

  /** The set of group ids the given user is a direct member of. Cached until invalidated. */
  public Set<UUID> groupIdsForUser(UUID userId) {
    return groupIdsByUser.get(userId, membershipRepository::findGroupIdsByUserId);
  }

  /**
   * The set of user ids that a grant to {@code subject} would reach. For a {@code USER} subject
   * this is just that user; for a {@code GROUP} subject it is the group's current membership,
   * scoped to {@link PermissionSubject#organizationId()} so a subject naming a group from another
   * organization resolves to nobody rather than leaking that group's members - membership is never
   * inherited downward beyond what is recorded here (see #237, #208).
   */
  public Set<UUID> resolveUserIds(PermissionSubject subject) {
    return switch (subject.type()) {
      case USER -> Set.of(subject.id());
      case GROUP ->
          membershipRepository.findUserIdsByGroupIdAndOrganizationId(
              subject.id(), subject.organizationId());
    };
  }

  public void invalidateUser(UUID userId) {
    groupIdsByUser.invalidate(userId);
  }

  public void invalidateUsers(Collection<UUID> userIds) {
    groupIdsByUser.invalidateAll(userIds);
  }
}
