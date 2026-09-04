package io.opaa.group;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.auth.UserRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
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
  private final UserRepository userRepository;
  private final ObjectProvider<GroupMembershipChangeListener> changeListeners;
  private final Cache<UUID, Set<UUID>> groupIdsByUser;

  public GroupMembershipResolver(
      GroupMembershipRepository membershipRepository,
      UserRepository userRepository,
      ObjectProvider<GroupMembershipChangeListener> changeListeners) {
    this.membershipRepository = membershipRepository;
    this.userRepository = userRepository;
    this.changeListeners = changeListeners;
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
   * The set of user ids that a grant to {@code subject} would reach, scoped to {@link
   * PermissionSubject#organizationId()} in both branches: for a {@code GROUP} subject it is the
   * group's current membership, filtered to that organization at the query in {@link
   * GroupMembershipRepository#findUserIdsByGroupIdAndOrganizationId} - membership is never
   * inherited downward beyond what is recorded there (see #237, #208). For a {@code USER} subject
   * it is that one user, but only if the user actually belongs to the given organization; otherwise
   * the empty set. This is the one place every future caller (#202) goes through, so it is
   * deliberately not left to each caller to re-check the boundary for the {@code USER} case - the
   * extra lookup costs one indexed hit on the hot path, which is cheaper than repeating the
   * organization-boundary bug class that #199 had to fix in review.
   */
  public Set<UUID> resolveUserIds(PermissionSubject subject) {
    return switch (subject.type()) {
      case USER -> resolveUserSubject(subject);
      case GROUP ->
          membershipRepository.findUserIdsByGroupIdAndOrganizationId(
              subject.id(), subject.organizationId());
    };
  }

  private Set<UUID> resolveUserSubject(PermissionSubject subject) {
    // subject.id() rather than the loaded user's own getId(): a repository match by id already
    // guarantees they are equal, and using subject.id() keeps this independent of whichever
    // fields a test double happens to populate on the returned User.
    boolean belongsToOrganization =
        userRepository
            .findById(subject.id())
            .map(user -> user.getOrganizationId().equals(subject.organizationId()))
            .orElse(false);
    return belongsToOrganization ? Set.of(subject.id()) : Set.of();
  }

  /**
   * Evicts the user's cached groups and tells every {@link GroupMembershipChangeListener} - the one
   * place a membership change reaches everything that depends on a person's rights.
   */
  public void invalidateUser(UUID userId) {
    groupIdsByUser.invalidate(userId);
    notifyListeners(Set.of(userId));
  }

  public void invalidateUsers(Collection<UUID> userIds) {
    groupIdsByUser.invalidateAll(userIds);
    notifyListeners(userIds);
  }

  private void notifyListeners(Collection<UUID> userIds) {
    changeListeners.orderedStream().forEach(listener -> listener.onMembershipChanged(userIds));
  }
}
