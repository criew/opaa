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
 * KnowledgeLibraryService#createLibrary}, which grants the creator {@link AssetRole#OWNER}
 * explicitly instead of deriving it from the owner columns) or from organization-wide visibility.
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
   * Whether the user may rename, change visibility/listed, or delete - requires {@link
   * AssetRole#MANAGER}.
   */
  public boolean canManage(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    return atLeast(effectiveRole(library, userId, systemAdmin), AssetRole.MANAGER);
  }

  /**
   * The highest {@link AssetRole} the user holds on the library, or {@code null} if none. System
   * libraries are fail-closed to system admins regardless of any grant, mirroring the previous
   * behaviour this class replaces. Organization-wide visibility grants {@link AssetRole#VIEWER} to
   * every user of the same organization - the same level the pre-#202 {@code canRead} granted for
   * {@code LibraryVisibility#ORGANIZATION}, kept unchanged here since #202's mandate is fixing the
   * group-ownership overreach, not narrowing this path.
   */
  public AssetRole effectiveRole(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    if (library.isSystemLibrary()) {
      return systemAdmin ? AssetRole.OWNER : null;
    }
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
