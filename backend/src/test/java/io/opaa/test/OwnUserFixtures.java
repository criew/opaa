package io.opaa.test;

import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.library.AssetGrantHistoryRepository;
import io.opaa.space.SpaceRepository;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Removes exactly the users a test method created, derived as the difference to a snapshot taken
 * before it ran - the whole suite shares one database, so a blanket {@code deleteAll()} over {@code
 * users}, {@code spaces} or the history tables would take a sibling class's still-needed rows with
 * it.
 *
 * <p>Covers users, the spaces they are a member of, and the two permission-history tables that
 * reference users with RESTRICT. Deliberately <b>not</b> {@code asset_grants}, {@code chats},
 * libraries or {@code group_memberships}: a class that creates those removes them itself, scoped to
 * its own ids, and a half-hearted sweep here would turn a loud RESTRICT violation into a silent
 * partial cleanup.
 */
public final class OwnUserFixtures {

  private final UserRepository users;
  private final SpaceRepository spaces;
  private final AssetGrantHistoryRepository grantHistory;
  private final GroupMembershipHistoryRepository membershipHistory;

  OwnUserFixtures(
      UserRepository users,
      SpaceRepository spaces,
      AssetGrantHistoryRepository grantHistory,
      GroupMembershipHistoryRepository membershipHistory) {
    this.users = users;
    this.spaces = spaces;
    this.grantHistory = grantHistory;
    this.membershipHistory = membershipHistory;
  }

  /** Snapshot for a {@code @BeforeEach}: every user that is none of this test method's business. */
  public Set<UUID> existingUserIds() {
    return users.findAll().stream().map(User::getId).collect(Collectors.toSet());
  }

  /**
   * Removes every user absent from {@code foreignUserIds}, together with every space they are a
   * member of - not only the ones they own, so a caller that adds its user to a foreign space would
   * delete that space too.
   *
   * @param foreignUserIds the snapshot from {@link #existingUserIds()}; {@code null} is refused,
   *     because an accidentally empty snapshot would make this a suite-wide {@code deleteAll()}
   */
  public void removeUsersCreatedSince(Set<UUID> foreignUserIds) {
    Objects.requireNonNull(
        foreignUserIds, "call existingUserIds() in @BeforeEach and keep its result");
    List<UUID> own =
        users.findAll().stream()
            .map(User::getId)
            .filter(id -> !foreignUserIds.contains(id))
            .toList();
    if (own.isEmpty()) {
      return;
    }
    // Space#memberships cascades, so deleting the space takes its membership rows with it.
    spaces.deleteAll(
        own.stream()
            .flatMap(id -> spaces.findDistinctByMembershipsUserId(id).stream())
            .distinct()
            .toList());
    // The permission-history tables reference users with RESTRICT: they go before the users.
    grantHistory.deleteBySubjectUserIdIn(own);
    membershipHistory.deleteByUserIdIn(own);
    users.deleteAllById(own);
  }
}
