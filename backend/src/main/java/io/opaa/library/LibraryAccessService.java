package io.opaa.library;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.PermissionSubjectType;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link AssetRole} a user effectively holds on a {@link KnowledgeLibrary}, and the
 * set of libraries a user may read - the linchpin of #202
 * (docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten). Replaces {@code
 * KnowledgeLibraryService}'s former {@code canRead}/{@code canManage}, which the class Javadoc
 * there marked as a deliberately coarse #201 interim to be replaced, not extended - in particular
 * it let every member of a group-owned library manage it, growing without a human decision point as
 * a directory-synchronised group's membership grows (#237). Under this class, management rights
 * come exclusively from an explicit {@link AssetGrant} (see {@code
 * KnowledgeLibraryService#createLibrary}, which grants the creator {@link AssetRole#OWNER} and, for
 * a group-owned library, additionally grants the owning group {@link AssetRole#MANAGER} - never
 * {@code OWNER} to the group, which would reintroduce the same unbounded, non-downgradable grant
 * this class replaced #201's coarse check to avoid, see that method's Javadoc) or from
 * organization-wide visibility.
 *
 * <p>Two access paths, deliberately not unified:
 *
 * <ul>
 *   <li>{@link #effectiveRole}, backing {@code canRead}/{@code canManage} for the library CRUD
 *       endpoints, is a single-library lookup on the hot path of every such request and is cached
 *       per library id, invalidated after commit whenever a grant on that library changes -
 *       mirroring {@link GroupMembershipResolver}'s cache and invalidation pattern.
 *   <li>{@link #readableLibraryIds}, backing the permission-aware vector search filter (#202's
 *       actual acceptance criteria), is deliberately <b>not</b> cached: it is a single indexed
 *       query per call, so a revoked grant takes effect on the very next query without depending on
 *       a second cache-invalidation path being correct - the search filter is where a stale cache
 *       would leak data, not merely delay a UI refresh.
 * </ul>
 */
@Component
public class LibraryAccessService {

  private final AssetGrantRepository grantRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final GroupMembershipResolver membershipResolver;
  private final Cache<UUID, List<AssetGrant>> grantsByLibrary;

  public LibraryAccessService(
      AssetGrantRepository grantRepository,
      KnowledgeLibraryRepository libraryRepository,
      GroupMembershipResolver membershipResolver) {
    this.grantRepository = grantRepository;
    this.libraryRepository = libraryRepository;
    this.membershipResolver = membershipResolver;
    // Same reasoning as GroupMembershipResolver#groupIdsByUser: a stale entry only ever grants
    // access a moment too long between a completed transaction's invalidation and the next read,
    // never too little - invalidateLibrary below, called post-commit, is the primary correctness
    // mechanism. Time-based expiry is a safety net only.
    this.grantsByLibrary =
        Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofMinutes(10)).build();
  }

  /**
   * Whether the user may see a library's configuration (name, description, owner, document list) -
   * requires at least {@link AssetRole#VIEWER}. Not the same as being able to use the library in a
   * query, which only requires {@link AssetRole#USER} and is what {@link #readableLibraryIds}
   * grants; see docs/features/spaces-and-assets.md#asset-rollen for the distinction.
   */
  public boolean canRead(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    return atLeast(effectiveRole(library, userId, systemAdmin), AssetRole.VIEWER);
  }

  /**
   * Whether the user may rename, change visibility/listed, or manage grants - requires {@link
   * AssetRole#MANAGER}. Deliberately <b>not</b> sufficient for deleting the library or (once it
   * exists) transferring ownership - see {@link #canDelete} and {@link AssetRole#OWNER}'s Javadoc
   * ("additionally delete the asset and transfer ownership"). Code review round 3 of #202: this
   * method's own Javadoc used to list "delete" here, and {@code
   * KnowledgeLibraryService#deleteLibrary} called it directly - the exact gap that let a group's
   * {@code MANAGER} grant (post round-2's group-gets-MANAGER fix) delete a library it could never
   * have downgraded or revoked the {@code OWNER} grant on, taking that grant down with it.
   */
  public boolean canManage(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    return atLeast(effectiveRole(library, userId, systemAdmin), AssetRole.MANAGER);
  }

  /**
   * Whether the user may delete the library (and, once it exists, transfer its ownership) -
   * requires {@link AssetRole#OWNER}, one level above {@link #canManage}. Split out in #202 code
   * review round 3: before this method existed, {@code KnowledgeLibraryService#deleteLibrary}
   * called {@link #canManage}, so a group's {@code MANAGER} grant (round 2's fix for the
   * group-owned-library case) could delete the library outright - taking every grant on it,
   * including the creator's {@code OWNER} grant, down with it via {@code
   * fk_asset_grants_library_organization}'s {@code ON DELETE CASCADE} (migration 013). That is
   * strictly worse than the escalation the round-1/round-2 guards close: those guards stop a {@code
   * MANAGER} from touching the {@code OWNER} grant directly, but deleting the library was an
   * untouched detour around them. For a migrated, backfilled group-owned library - deliberately
   * left without any {@code OWNER} grant at all, see 013-asset-grants.yaml's backfill comment -
   * this meant nobody could downgrade the group's grant, yet any member could still delete the
   * library wholesale; this method closes that gap by requiring a role nobody currently holds on
   * such a library, matching the "Nachfolge offen" intent (only a system admin, or a person granted
   * {@code OWNER} once a curator is assigned, may delete or transfer such a library).
   */
  public boolean canDelete(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    return atLeast(effectiveRole(library, userId, systemAdmin), AssetRole.OWNER);
  }

  /**
   * The highest {@link AssetRole} the user holds on the library, or {@code null} if none.
   * Organization-wide visibility grants {@link AssetRole#VIEWER} to every user of the same
   * organization - the same level the pre-#202 {@code canRead} granted for {@code
   * LibraryVisibility#ORGANIZATION}, kept unchanged here since #202's mandate is fixing the
   * group-ownership overreach, not narrowing this path.
   *
   * <p>System libraries used to short-circuit here to "system admins only, regardless of any
   * grant". That special case is gone (#406): it made this method disagree with {@link
   * #readableLibraryIds}, which never had it, so the same library could be readable through the
   * search and forbidden through the library API - two answers to one question. The formula in
   * docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten knows no such exception, and
   * both paths now implement it alike.
   *
   * <p>The fail-closed guarantee #201 set for the migration target is unaffected, because it never
   * depended on this branch: the system library is seeded {@code PRIVATE} with no grants
   * (012-seed-system-library), so the formula excludes everyone by itself. What changes is that
   * opening it is now a deliberate decision rather than an impossibility - and one only a system
   * admin can take, since granting or changing visibility requires {@code MANAGER}, which on a
   * library with no owner and no grants nobody else can hold. The specification asks for exactly
   * that: no organization-wide default, not a bulk of documents no one can ever reach.
   */
  public AssetRole effectiveRole(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    if (systemAdmin) {
      return AssetRole.OWNER;
    }

    AssetRole best = null;
    if (library.getVisibility() == LibraryVisibility.ORGANIZATION) {
      best = AssetRole.VIEWER;
    }

    Instant now = Instant.now();
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);
    for (AssetGrant grant :
        grantsByLibrary.get(library.getId(), grantRepository::findByLibraryId)) {
      if (grant.isExpired(now)) {
        continue;
      }
      boolean reaches =
          (grant.getSubjectType() == PermissionSubjectType.USER
                  && grant.getSubjectUserId().equals(userId))
              || (grant.getSubjectType() == PermissionSubjectType.GROUP
                  && groupIds.contains(grant.getSubjectGroupId()));
      if (reaches && (best == null || grant.getRole().atLeast(best))) {
        best = grant.getRole();
      }
    }
    return best;
  }

  /**
   * Every library id readable by the user in {@code organizationId}: direct grants, group grants
   * for the groups the user currently belongs to, and every organization-wide library - exactly the
   * formula in docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten. Space
   * associations deliberately do not appear anywhere in this computation, per the same
   * specification section. No system-admin bypass: the vector search always reads with the calling
   * user's own rights, with no second rights context (see #202 scope and ADR-0008 §5) - unlike
   * {@link #effectiveRole}, which still fail-opens system admins for library administration.
   *
   * <p>That remaining asymmetry is intentional and points the safe way: an admin may administer
   * every library but retrieves only from those the formula grants them, so nothing an admin reads
   * in a chat can come from a library they were not granted. Which libraries the formula covers is
   * decided identically in both methods since #406 - the system library no longer being an
   * exception here or there.
   */
  public Set<UUID> readableLibraryIds(UUID userId, UUID organizationId) {
    Instant now = Instant.now();
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);

    Set<UUID> readable = new HashSet<>();
    readable.addAll(
        grantRepository.findReadableLibraryIdsByDirectGrant(userId, organizationId, now));
    if (!groupIds.isEmpty()) {
      readable.addAll(
          grantRepository.findReadableLibraryIdsByGroupGrant(groupIds, organizationId, now));
    }
    libraryRepository
        .findByOrganizationIdAndVisibility(organizationId, LibraryVisibility.ORGANIZATION)
        .forEach(library -> readable.add(library.getId()));
    return readable;
  }

  /**
   * Evicts the cached grant list for a library, called after commit (or rollback) whenever one of
   * its grants changes - see {@code AssetGrantService#invalidateAfterCommit} for why "after commit"
   * rather than inline.
   */
  public void invalidateLibrary(UUID libraryId) {
    grantsByLibrary.invalidate(libraryId);
  }

  private static boolean atLeast(AssetRole role, AssetRole required) {
    return role != null && role.atLeast(required);
  }
}
