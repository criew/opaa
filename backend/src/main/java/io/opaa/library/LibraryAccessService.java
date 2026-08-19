package io.opaa.library;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.PermissionSubjectType;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
   * requires at least {@link AssetRole#VIEWER}. Historically distinct from being able to use the
   * library in a query, which required only a now-removed {@code USER} rank below {@code VIEWER} -
   * #330 dropped that rank (see {@link AssetRole}'s Javadoc: unenforceable for an agent, and
   * largely moot for a library since cited answers expose document titles anyway), so both
   * questions now resolve to the same threshold. {@link #readableLibraryIds} still exists as the
   * search-facing counterpart of this method - see its own Javadoc for why the two are not unified
   * into one call.
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
   * Whether the user may target this library as the destination of an indexing run (#419) -
   * requires {@link AssetRole#EDITOR}, one level below {@link #canManage}. A directory or URL
   * indexing run writes documents into the library, which is a content change, not a configuration
   * change (rename, visibility, grants) - {@code EDITOR} is the role {@link AssetRole}'s Javadoc
   * reserves for "change the configuration" one level above {@code VIEWER}, and is deliberately the
   * floor here rather than {@code MANAGER}: requiring sharing/grant rights just to add documents
   * would force every indexing operator to also be able to reshape the library's access list.
   */
  public boolean canEdit(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    return atLeast(effectiveRole(library, userId, systemAdmin), AssetRole.EDITOR);
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
   * <p>The well-known system library used to short-circuit here to "system admins only, regardless
   * of any grant". That special case was removed in #406, before this method ever disagreed with
   * {@link #readableLibraryIds} (which never had it) - the same library could otherwise be readable
   * through the search and forbidden through the library API, two answers to one question. #521
   * later deleted the system library itself outright, so there is nothing left this formula could
   * special-case even if it wanted to: every library reaches this method through the same, single
   * path the specification in docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten
   * describes, with no exception.
   */
  public AssetRole effectiveRole(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    if (systemAdmin) {
      return AssetRole.OWNER;
    }

    AssetRole organizationWideFloor =
        library.getVisibility() == LibraryVisibility.ORGANIZATION ? AssetRole.VIEWER : null;
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);
    return bestRole(
        grantsByLibrary.get(library.getId(), grantRepository::findByLibraryId),
        userId,
        groupIds,
        Instant.now(),
        organizationWideFloor);
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
   * decided identically in both methods since #406, with no library-specific exception - see {@link
   * #effectiveRole}'s own Javadoc for the history of the one exception that used to exist.
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
   * The effective {@link AssetRole} for every one of {@code libraries}, for {@code userId} - the
   * {@code listLibraries} counterpart of {@link #effectiveRole}, deliberately not built by calling
   * that method once per library (#425 code review, finding 1 and nit 4):
   *
   * <ul>
   *   <li><b>Correctness (finding 1):</b> {@code listLibraries} membership comes from {@link
   *       #readableLibraryIds}, which is deliberately uncached so a just-granted or just-revoked
   *       right is reflected immediately. {@link #effectiveRole} reads the separately cached {@link
   *       #grantsByLibrary}, invalidated only after commit. Combining the two - membership from the
   *       fresh path, role from the stale one - let a library appear in the list with no grant the
   *       cache yet knew about, so {@link #effectiveRole} returned {@code null} for a response
   *       field the OpenAPI specification declares required. This method reads every grant for
   *       {@code libraries} in the one query below, the same freshness guarantee {@link
   *       #readableLibraryIds} already gives its own membership decision, and floors the result at
   *       {@link AssetRole#VIEWER}: every library in {@code libraries} is assumed to already be in
   *       the caller's {@link #readableLibraryIds}, which the formula guarantees is reachable only
   *       at {@code VIEWER} or above, so a role that still resolves to {@code null} here reflects a
   *       caller-supplied library outside that guarantee, not a legitimately absent grant.
   *   <li><b>Performance (nit 4):</b> one query for N libraries instead of up to N queries on a
   *       cold cache (e.g. after a restart, or once {@code grantsByLibrary}'s ten-minute expiry has
   *       passed) - the list has no pagination (#418 scope), so it grows with every
   *       organization-wide library.
   * </ul>
   *
   * <p><b>Never bypasses to {@link AssetRole#OWNER} for a system admin</b> - unlike {@link
   * #effectiveRole}. {@code listLibraries} membership itself never bypasses (see {@link
   * #readableLibraryIds}'s Javadoc), so a bypassed role here would mislabel an
   * administratively-reached library as one the admin actually owns or manages - the exact
   * confusion #418's scope warns the frontend must be able to avoid. See {@code myRole}'s
   * description in the OpenAPI specification for the caller-facing consequence: a system admin
   * distinguishes "I own/manage this" from "I can see this because I administer everything" via
   * their own known admin status, not via this field.
   */
  public Map<UUID, AssetRole> effectiveRolesForReadableLibraries(
      List<KnowledgeLibrary> libraries, UUID userId) {
    Instant now = Instant.now();
    Set<UUID> libraryIds =
        libraries.stream().map(KnowledgeLibrary::getId).collect(Collectors.toSet());
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);
    Map<UUID, List<AssetGrant>> grantsByLibraryId =
        grantRepository.findByLibraryIdIn(libraryIds).stream()
            .collect(Collectors.groupingBy(AssetGrant::getLibraryId));

    Map<UUID, AssetRole> roles = new HashMap<>();
    for (KnowledgeLibrary library : libraries) {
      AssetRole organizationWideFloor =
          library.getVisibility() == LibraryVisibility.ORGANIZATION ? AssetRole.VIEWER : null;
      AssetRole best =
          bestRole(
              grantsByLibraryId.getOrDefault(library.getId(), List.of()),
              userId,
              groupIds,
              now,
              organizationWideFloor);
      // Every library here is assumed to already be in the caller's readableLibraryIds, which the
      // formula guarantees is reachable only at VIEWER or above - see this method's own Javadoc.
      roles.put(library.getId(), best != null ? best : AssetRole.VIEWER);
    }
    return roles;
  }

  /**
   * The single rights-resolution formula both {@link #effectiveRole} and {@link
   * #effectiveRolesForReadableLibraries} apply - the same computation over two different grant
   * sources (a single cached library's grants vs. a batch-loaded map across many), extracted after
   * #425 code review so the formula itself can never drift between the two call sites the way the
   * divergence #418 itself closes once did between {@code listLibraries} and {@code
   * readableLibraryIds}. Highest role among {@code seed} (the caller's starting floor, e.g.
   * organization-wide visibility, or {@code null} for none) and every non-expired grant in {@code
   * grants} that reaches {@code userId} - directly, or via one of {@code groupIds}.
   */
  private static AssetRole bestRole(
      List<AssetGrant> grants, UUID userId, Set<UUID> groupIds, Instant now, AssetRole seed) {
    AssetRole best = seed;
    for (AssetGrant grant : grants) {
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
