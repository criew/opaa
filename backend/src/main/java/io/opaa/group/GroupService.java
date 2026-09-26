package io.opaa.group;

import static java.util.stream.Collectors.toSet;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.Capability;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.NotificationType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.OrganizationScopedLoader;
import io.opaa.common.ValidationException;
import io.opaa.group.sync.DirectorySyncStatus;
import io.opaa.group.sync.DirectorySyncStatusRepository;
import io.opaa.notification.NotificationService;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetOwnershipDirectory;
import io.opaa.permission.CapabilityGrantRepository;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.GroupMembershipHistoryCause;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSizeProperties;
import io.opaa.permission.GroupSpaceMembershipDirectory;
import io.opaa.permission.PermissionHistoryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manages groups as permission subjects. The right to maintain one is decided <b>per group</b>
 * rather than by role (#1814, ADR-0036 Entscheidung 4): its stewards may, and so may a system
 * administrator; anybody else gets the answer an unknown group gets, {@code 404}. The organization
 * boundary is enforced on top of that the same way {@code SpaceService} does it, because a system
 * admin exists per organization and must never see or touch another organization's groups.
 *
 * <p>The one exception to "a system administrator may" is the protection mark of ADR-0036,
 * Entscheidung 9: it is set and released by the body concerned itself, never by the administration,
 * so {@link #setProtection} refuses an administrator who is no steward.
 *
 * <p>Only {@link GroupKind#AD_HOC} groups can be created, renamed, deleted or have their membership
 * managed here. {@link GroupKind#ORG_UNIT} groups are synchronised from the directory (#237) and
 * are read-only through this service.
 *
 * <p>Deleting a group that owns an asset is blocked until ownership is transferred (see the feature
 * spec's "Eigentuemerschaft und Verwaisung" and issue #200's acceptance criteria). Which asset
 * types exist is not this class's business: every one of them contributes an {@link
 * AssetOwnershipDirectory}, and {@link #deleteGroup} asks all of them.
 *
 * <p>Deleting a group that merely <em>holds a grant</em> - not necessarily owns anything - is
 * blocked too, and independently of the ownership check above. {@code
 * fk_asset_grants_subject_group_organization} is RESTRICT, exactly like the owner keys; without the
 * check in {@link #deleteGroup}, the everyday case the feature spec's "Freigabestufen und
 * Auffindbarkeit" describes - "an Abteilung 5 freigeben" is a grant to the group representing
 * Abteilung 5, not ownership - would be refused by the constraint alone, with the generic
 * foreign-key message {@code GlobalExceptionHandler} turns a {@code
 * DataIntegrityViolationException} into and without naming what still holds the group.
 */
@Service
@Transactional(readOnly = true)
public class GroupService {

  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;

  /** The same floor and ceiling the account search carries, for the same reasons (#778). */
  private static final int MIN_SEARCH_QUERY_LENGTH = 2;

  private static final int MAX_SEARCH_RESULTS = 20;

  /**
   * How many rows the substring search may read before the visibility rule thins them out. Five
   * windows of the result cap: enough that a caller whose matches are mostly invisible still gets a
   * full page, and still a hard bound - the database never hands out the whole organization.
   */
  private static final int MAX_SEARCH_SCAN = MAX_SEARCH_RESULTS * 5;

  /**
   * The stable {@code code} of the {@code 403} anybody but the system administration gets for the
   * protection mark - where they already know the group, and so learn nothing new from the answer.
   */
  public static final String PROTECTION_ADMIN_ONLY = "PROTECTION_ADMIN_ONLY";

  private final GroupRepository groupRepository;
  private final GroupStewardRepository stewardRepository;
  private final UserRepository userRepository;
  private final OidcProviderRepository providerRepository;
  private final DirectorySyncStatusRepository directorySyncStatusRepository;
  private final GroupMembershipResolver membershipResolver;
  private final GroupSizeProperties groupSizeProperties;
  private final GroupSpaceMembershipDirectory spaceMembershipDirectory;
  private final List<AssetOwnershipDirectory> assetOwnershipDirectories;
  private final AssetGrantRepository grantRepository;
  private final CapabilityGrantRepository capabilityGrantRepository;
  private final CapabilityService capabilityService;
  private final PermissionHistoryService permissionHistoryService;
  private final NotificationService notificationService;
  private final AuditEventRecorder auditEventRecorder;

  public GroupService(
      GroupRepository groupRepository,
      GroupStewardRepository stewardRepository,
      UserRepository userRepository,
      OidcProviderRepository providerRepository,
      DirectorySyncStatusRepository directorySyncStatusRepository,
      GroupMembershipResolver membershipResolver,
      GroupSizeProperties groupSizeProperties,
      GroupSpaceMembershipDirectory spaceMembershipDirectory,
      List<AssetOwnershipDirectory> assetOwnershipDirectories,
      AssetGrantRepository grantRepository,
      CapabilityGrantRepository capabilityGrantRepository,
      CapabilityService capabilityService,
      PermissionHistoryService permissionHistoryService,
      NotificationService notificationService,
      AuditEventRecorder auditEventRecorder) {
    this.groupRepository = groupRepository;
    this.stewardRepository = stewardRepository;
    this.userRepository = userRepository;
    this.providerRepository = providerRepository;
    this.directorySyncStatusRepository = directorySyncStatusRepository;
    this.membershipResolver = membershipResolver;
    this.groupSizeProperties = groupSizeProperties;
    this.spaceMembershipDirectory = spaceMembershipDirectory;
    this.assetOwnershipDirectories = assetOwnershipDirectories;
    this.grantRepository = grantRepository;
    this.capabilityGrantRepository = capabilityGrantRepository;
    this.capabilityService = capabilityService;
    this.permissionHistoryService = permissionHistoryService;
    this.notificationService = notificationService;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Transactional
  public GroupDetail createGroup(GroupCreation creation, CurrentUser caller) {
    capabilityService.requireCapability(caller, Capability.CREATE_INTERNAL_GROUP);
    String normalizedName = validateName(creation.name());
    validateDescription(creation.description());

    Group group =
        Group.internal(caller.organizationId(), normalizedName, creation.description(), null);
    Group saved = groupRepository.save(group);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(saved.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.GROUP_CREATED)
            .object(AuditObjectType.GROUP, saved.getId(), saved.getName())
            .outcome(AuditOutcome.SUCCESS)
            .build());
    // In the same transaction as the group itself: an internal group without a steward is the
    // "Nachfolge offen" state of ADR-0036, Entscheidung 6, and creating one is not how it should
    // ever be entered.
    appoint(saved, caller.id(), caller);
    return toGroupDetail(saved, caller);
  }

  public List<GroupOverview> listGroups(CurrentUser caller) {
    return toOverviews(
        groupRepository.findByOrganizationIdWithMemberships(caller.organizationId()));
  }

  /**
   * Lists the groups the given user is a direct member of - not admin-restricted, unlike {@link
   * #listGroups}. Backs {@code GET /api/v1/me/groups}, which the frontend's library-creation dialog
   * uses to offer only groups the caller can actually own a library through (see {@code
   * KnowledgeLibraryService#createLibrary}, which rejects a GROUP owner the caller is not a member
   * of).
   *
   * <p>Excludes dissolved groups: a dissolved group's membership is frozen rather than cleared (see
   * {@link Group#isDissolved()}), so it would otherwise still surface here. {@code
   * KnowledgeLibraryService#createLibrary} does not currently check {@code isDissolved()} itself
   * before writing the owner grant (see #201/#202) - so today, offering a dissolved group here is
   * the only thing standing between the picker and a library owned by a group that no longer
   * organisationally exists.
   *
   * <p>Also filters to the caller's organization, mirroring {@link #listGroups}: as of migration
   * 047 this filter is structurally unreachable, not merely unexercised - {@code
   * fk_group_memberships_user_organization} (composite on {@code user_id, organization_id}) and
   * {@code fk_group_memberships_group_organization} (migration 009, composite on {@code group_id,
   * organization_id}) together force a membership row's {@code organization_id} to match both the
   * member's and the group's actual organization, so no row this filter would ever reject can exist
   * in the first place - see {@code
   * GroupServiceIntegrationTest#aMembershipRowCanNeverCrossAnOrganizationBoundaryAtTheDatabaseLevel}
   * (#308), which proves the database rejects constructing one directly. Left in place anyway as a
   * second, independent defense line the class Javadoc's philosophy calls for - one that does not
   * rely on the schema invariant above continuing to hold, in case a future migration ever loosens
   * it.
   */
  public List<GroupOverview> listMyGroups(CurrentUser caller) {
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(caller.id());
    if (groupIds.isEmpty()) {
      return List.of();
    }
    return toOverviews(
        groupRepository.findAllByIdWithMemberships(groupIds).stream()
            .filter(group -> !group.isDissolved())
            .filter(group -> group.getOrganizationId().equals(caller.organizationId()))
            .toList());
  }

  /**
   * The groups the caller may name as a grant subject or a space member (#1820, ADR-0036
   * Entscheidung 9). Every provider group of the organization is offered; an internal group only
   * once its stewards released it for use - unless the caller is a member, a steward or a system
   * administrator, for whom it was never invisible.
   *
   * <p>A protected group is not findable by a substring: it appears only when the query is its
   * complete name, and then without any size. An ineffective group - dissolved, provider switched
   * off, or a token group its provider no longer maintains - is returned as not selectable with the
   * reason instead of being dropped, so the selection can say why.
   *
   * <p>This list applies the same rule the write paths apply ({@code
   * GroupSubjectDirectory#isSelectableBy}); it is the convenience, never the enforcement.
   */
  public List<SelectableGroup> searchSelectableGroups(String query, CurrentUser caller) {
    String text = query == null ? "" : query.trim();
    if (text.length() < MIN_SEARCH_QUERY_LENGTH) {
      return List.of();
    }
    Set<UUID> ownGroupIds = membershipResolver.groupIdsForUser(caller.id());
    Set<UUID> stewardedGroupIds = stewardRepository.findGroupIdsByUserId(caller.id());
    Map<UUID, GroupProviderView> providers = new HashMap<>();
    List<SelectableGroup> found = new ArrayList<>();
    // The protected groups first, and from their own query: they are reached only by their
    // complete name, and asking for them separately keeps the bounded window of the substring
    // search from deciding whether that one hit appears (ADR-0036, Entscheidung 9).
    List<Group> candidates =
        new ArrayList<>(
            groupRepository.findProtectedByOrganizationIdAndName(
                caller.organizationId(), text, PageRequest.of(0, MAX_SEARCH_RESULTS)));
    candidates.addAll(
        groupRepository.searchByOrganizationIdAndText(
            caller.organizationId(), likePattern(text), PageRequest.of(0, MAX_SEARCH_SCAN)));
    for (Group group : candidates) {
      if (!isVisibleInSelection(group, caller, ownGroupIds, stewardedGroupIds)) {
        continue;
      }
      found.add(toSelectableGroup(group, providerOf(group, providers), group.getName()));
      if (found.size() == MAX_SEARCH_RESULTS) {
        break;
      }
    }
    return List.copyOf(found);
  }

  /**
   * One group by its id, under the rule {@link #searchSelectableGroups} applies (#1820). The way in
   * for the caller who types a designation by hand: the group is resolved <b>before</b> a right is
   * granted, so origin and provider reach the interface on that path too - without them, the
   * question back for a group of an external provider would be missing on exactly the path that
   * shows the least (ADR-0036, Entscheidung 2).
   *
   * <p>A group this caller may not name answers like one that does not exist. A protected group
   * comes back <b>without its name</b>: the caller did not name it here, and handing a name back
   * for an id would undo the namelessness of Entscheidung 9.
   */
  public Optional<SelectableGroup> resolveSelectableGroup(UUID groupId, CurrentUser caller) {
    Group group = groupRepository.findById(groupId).orElse(null);
    if (group == null || !group.getOrganizationId().equals(caller.organizationId())) {
      return Optional.empty();
    }
    Set<UUID> ownGroupIds = membershipResolver.groupIdsForUser(caller.id());
    Set<UUID> stewardedGroupIds = stewardRepository.findGroupIdsByUserId(caller.id());
    if (!isVisibleInSelection(group, caller, ownGroupIds, stewardedGroupIds)) {
      return Optional.empty();
    }
    return Optional.of(
        toSelectableGroup(
            group,
            providerOf(group, new HashMap<>()),
            group.isProtectedGroup() ? null : group.getName()));
  }

  /**
   * Whether this caller may name the group at all. The protection is <b>not</b> decided here: a
   * protected group is invisible to the substring search by construction (its own query answers
   * only the complete name), and by id it is answered without its name.
   */
  private boolean isVisibleInSelection(
      Group group, CurrentUser caller, Set<UUID> ownGroupIds, Set<UUID> stewardedGroupIds) {
    boolean related =
        caller.isSystemAdmin()
            || ownGroupIds.contains(group.getId())
            || stewardedGroupIds.contains(group.getId());
    return group.isSelectableAsSubject() || related;
  }

  private GroupProviderView providerOf(Group group, Map<UUID, GroupProviderView> cache) {
    if (group.getProviderId() == null) {
      return null;
    }
    return cache.computeIfAbsent(
        group.getProviderId(),
        id ->
            providerRepository
                .findById(id)
                .map(provider -> toProviderView(provider, group.getOrganizationId()))
                .orElse(null));
  }

  /**
   * The typed text as a LIKE pattern, with {@code %}, {@code _} and the escape character itself
   * neutralised - otherwise a query of two percent signs would match every group of the
   * organization, and a lone backslash would start an escape sequence instead of matching itself.
   */
  private static String likePattern(String text) {
    String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private SelectableGroup toSelectableGroup(
      Group group, GroupProviderView provider, String publishedName) {
    boolean providerDisabled = provider != null && !provider.enabled();
    boolean unmaintained = GroupStates.unmaintained(group, provider);
    boolean selectable = !group.isDissolved() && !providerDisabled && !unmaintained;
    if (group.isProtectedGroup()) {
      // Whom to ask instead of reading the member list (ADR-0036, Entscheidung 9): the stewards of
      // an internal group; for a provider group the system administration answers.
      return new SelectableGroup(
          group,
          publishedName,
          responsibleNamesOf(group),
          provider,
          null,
          false,
          false,
          selectable,
          group.isDissolved(),
          providerDisabled,
          unmaintained);
    }
    int active = membershipResolver.activeMemberCount(group.getId(), group.getOrganizationId());
    boolean small = active < groupSizeProperties.minimumGroupSize();
    return new SelectableGroup(
        group,
        publishedName,
        List.of(),
        provider,
        small ? null : Integer.valueOf(active),
        small,
        active == 0,
        selectable,
        group.isDissolved(),
        providerDisabled,
        unmaintained);
  }

  /**
   * The people a grant giver may ask about a protected group - never its members: the stewards of
   * an internal group, nobody for a provider group, for which the system administration answers.
   * Only a protected group pays for it, and only by its own query: the substring search never
   * returns one, so a result page carries at most the handful the caller named by their complete
   * designation.
   */
  private List<String> responsibleNamesOf(Group group) {
    if (!group.isInternal()) {
      return List.of();
    }
    List<UUID> userIds = stewardsOf(group.getId()).stream().map(GroupSteward::getUserId).toList();
    Map<UUID, String> displayNames = resolveDisplayNames(userIds);
    return userIds.stream().map(displayNames::get).filter(Objects::nonNull).toList();
  }

  /**
   * Resolves the origin of a whole list in one read of {@code oidc_providers} - a table with a
   * handful of rows, read once instead of once per group.
   */
  private List<GroupOverview> toOverviews(List<Group> groups) {
    return toOverviews(groups, providerViewsOf(groups));
  }

  /** The provider of every provider group in the list, each read once. */
  Map<UUID, GroupProviderView> providerViewsOf(List<Group> groups) {
    Set<UUID> providerIds =
        groups.stream().map(Group::getProviderId).filter(Objects::nonNull).collect(toSet());
    Map<UUID, GroupProviderView> byId = new HashMap<>();
    if (!providerIds.isEmpty()) {
      UUID organizationId = groups.get(0).getOrganizationId();
      providerRepository
          .findAllById(providerIds)
          .forEach(
              provider -> byId.put(provider.getId(), toProviderView(provider, organizationId)));
    }
    return byId;
  }

  /**
   * Stewards for exactly these groups, with the provider views already read - the paged list
   * resolves them for its page only.
   */
  List<GroupOverview> toOverviews(List<Group> groups, Map<UUID, GroupProviderView> byId) {
    if (groups.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<GroupStewardView>> stewardsByGroup = stewardsOf(groups);
    return groups.stream()
        .map(
            group ->
                new GroupOverview(
                    group,
                    stewardsByGroup.getOrDefault(group.getId(), List.of()),
                    byId.get(group.getProviderId())))
        .toList();
  }

  /**
   * The stewards of a whole list in one read of {@code group_stewards} - a member sees the people
   * responsible for their own groups by name (ADR-0036, Entscheidung 4), which would otherwise be
   * one query per row.
   */
  private Map<UUID, List<GroupStewardView>> stewardsOf(List<Group> groups) {
    List<GroupSteward> stewards =
        stewardRepository.findByGroupIdIn(groups.stream().map(Group::getId).toList());
    Map<UUID, List<GroupStewardView>> byGroup = new HashMap<>();
    for (GroupStewardView view : toStewardViews(stewards)) {
      byGroup.computeIfAbsent(view.steward().getGroupId(), key -> new ArrayList<>()).add(view);
    }
    return byGroup;
  }

  private GroupProviderView providerOf(Group group) {
    if (group.getProviderId() == null) {
      return null;
    }
    return providerRepository
        .findById(group.getProviderId())
        .map(provider -> toProviderView(provider, group.getOrganizationId()))
        .orElse(null);
  }

  /**
   * The last time this provider's directory was read, or null while its groups come from tokens -
   * the delay a member may see for themselves (ADR-0036, Entscheidung 3). Read per provider, not
   * per group: {@code toOverviews} resolves a whole list through the handful of provider rows.
   */
  private GroupProviderView toProviderView(OidcProvider provider, UUID organizationId) {
    Instant lastSyncAt =
        provider.isDirectorySyncEnabled()
            ? directorySyncStatusRepository
                .findByOrganizationIdAndProviderId(organizationId, provider.getId())
                .map(DirectorySyncStatus::getLastRunAt)
                .orElse(null)
            : null;
    return new GroupProviderView(
        provider.getId(),
        provider.getDisplayName(),
        provider.isExternal(),
        provider.isEnabled(),
        provider.groupMechanism(),
        provider.getDirectorySyncIntervalMinutes(),
        lastSyncAt);
  }

  /** The group detail, for its stewards and the system administration. */
  public GroupDetail getGroup(UUID groupId, CurrentUser caller) {
    return toGroupDetail(requireMaintainable(groupId, caller), caller);
  }

  /**
   * The internal groups the caller is responsible for - what "Meine Gruppen" shows, and the place
   * responsibility is handed over from (ADR-0036, Entscheidung 4).
   */
  public List<GroupOverview> listStewardedGroups(CurrentUser caller) {
    Set<UUID> groupIds = stewardRepository.findGroupIdsByUserId(caller.id());
    if (groupIds.isEmpty()) {
      return List.of();
    }
    return toOverviews(
        groupRepository.findAllByIdWithMemberships(groupIds).stream()
            .filter(group -> group.getOrganizationId().equals(caller.organizationId()))
            .toList());
  }

  public List<GroupStewardView> listStewards(UUID groupId, CurrentUser caller) {
    return toStewardViews(stewardsOf(requireMaintainable(groupId, caller).getId()));
  }

  /**
   * Appoints a further steward. Only a natural person of the same organization - a group as a
   * steward would be nesting through the back door (ADR-0036, Entscheidung 4) - and being a steward
   * makes nobody a member.
   */
  @Transactional
  public GroupStewardView appointSteward(UUID groupId, UUID userId, CurrentUser caller) {
    Group group = requireMaintainable(groupId, caller);
    rejectOrgUnit(group);
    requireUserInOrganization(userId, group.getOrganizationId());
    if (stewardRepository.existsByGroupIdAndUserId(groupId, userId)) {
      throw new ConflictException("Die Person ist bereits verantwortlich für diese Gruppe");
    }
    return new GroupStewardView(appoint(group, userId, caller), resolveDisplayName(userId));
  }

  /**
   * Dismisses a steward. A steward never leaves the group without one: handing responsibility over
   * means appointing the successor first (ADR-0036, Entscheidung 4). A system administrator may
   * dismiss the last one - an account leaving the house has to be releasable - and the group then
   * carries no steward until one is appointed again (#1819).
   */
  @Transactional
  public void dismissSteward(UUID groupId, UUID userId, CurrentUser caller) {
    Group group = requireMaintainable(groupId, caller);
    rejectOrgUnit(group);
    GroupSteward steward =
        stewardRepository
            .findByGroupIdAndUserId(groupId, userId)
            .orElseThrow(() -> new NotFoundException("Verantwortliche Person nicht gefunden"));
    if (!caller.isSystemAdmin() && stewardRepository.countByGroupId(groupId) <= 1) {
      throw new ConflictException(
          "Dies ist die letzte verantwortliche Person dieser Gruppe. Benennen Sie zuerst eine"
              + " Nachfolge und geben Sie die Verantwortung dann ab.");
    }
    stewardRepository.delete(steward);
    recordStewardshipEvent(AuditEventType.GROUP_STEWARD_DISMISSED, group, userId, caller);
  }

  /**
   * Releases an internal group for use by other people granting rights, or takes that back
   * (ADR-0036, Entscheidung 9). Taking it back removes the group from every selection; the grants
   * it already holds stay untouched, exactly as for a dissolved group.
   */
  @Transactional
  public GroupDetail setRelease(UUID groupId, boolean releasedForUse, CurrentUser caller) {
    Group group = requireMaintainable(groupId, caller);
    rejectOrgUnit(group);
    if (group.isReleasedForUse() != releasedForUse) {
      group.release(releasedForUse);
      groupRepository.save(group);
      recordReachEvent(AuditEventType.GROUP_RELEASE_CHANGED, group, "releasedForUse", caller);
    }
    return toGroupDetail(group, caller);
  }

  /**
   * Sets or releases the protection mark of ADR-0036, Entscheidung 9 (as amended): the system
   * administration alone decides it, for internal and provider groups alike. This is therefore the
   * one write path a provider group has at all, and the reason {@code rejectOrgUnit} does not guard
   * it.
   */
  @Transactional
  public GroupDetail setProtection(UUID groupId, boolean protectedGroup, CurrentUser caller) {
    Group group = requireProtectionAuthority(groupId, caller);
    if (group.isProtectedGroup() != protectedGroup) {
      group.markProtected(protectedGroup);
      groupRepository.save(group);
      recordReachEvent(AuditEventType.GROUP_PROTECTION_CHANGED, group, "protected", caller);
    }
    return toGroupDetail(group, caller);
  }

  /**
   * The group whose mark is about to change, for the system administration. Anybody else who
   * already knows the group - a steward or a member - is told why with {@code 403}; everybody else
   * gets the answer of an unknown group, so the existence of a protected group stays a secret.
   */
  private Group requireProtectionAuthority(UUID groupId, CurrentUser caller) {
    Group group = loadGroup(groupId, caller);
    if (caller.isSystemAdmin()) {
      return group;
    }
    boolean knowsTheGroup =
        userMembership(group, caller.id()) != null
            || stewardRepository.existsByGroupIdAndUserId(group.getId(), caller.id());
    if (!knowsTheGroup) {
      throw new NotFoundException("Gruppe nicht gefunden");
    }
    throw new AccessDeniedException(
        "Über den Schutz einer Gruppe entscheidet die Systemverwaltung.", PROTECTION_ADMIN_ONLY);
  }

  @Transactional
  public GroupDetail updateGroup(UUID groupId, GroupUpdate update, CurrentUser caller) {
    Group group = requireMaintainable(groupId, caller);
    rejectOrgUnit(group);

    String normalizedName = validateName(update.name());
    validateDescription(update.description());
    String previousName = group.getName();
    String previousDescription = group.getDescription();
    group.updateDetails(normalizedName, update.description());
    Group updated = groupRepository.save(group);
    boolean nameChanged = !Objects.equals(previousName, updated.getName());
    boolean descriptionChanged = !Objects.equals(previousDescription, updated.getDescription());
    if (nameChanged || descriptionChanged) {
      // #392 code review, finding 4: changedFields names which fields changed without carrying the
      // free-text description content itself into the append-only log - see
      // KnowledgeLibraryService#updateLibrary's identical treatment.
      List<String> changedFields = new ArrayList<>();
      if (nameChanged) {
        changedFields.add("name");
      }
      if (descriptionChanged) {
        changedFields.add("description");
      }
      auditEventRecorder.recordUserAction(
          AuditEvent.builder()
              .organizationId(updated.getOrganizationId())
              .actor(caller.id())
              .type(AuditEventType.GROUP_CHANGED)
              .object(AuditObjectType.GROUP, updated.getId(), updated.getName())
              .before(Map.of("changedFields", changedFields))
              .after(Map.of("changedFields", changedFields))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    return toGroupDetail(updated, caller);
  }

  @Transactional
  public void deleteGroup(UUID groupId, CurrentUser caller) {
    Group group = requireMaintainable(groupId, caller);
    rejectOrgUnit(group);
    // The owner foreign keys of the asset tables are RESTRICT: without this check, deleting a
    // group that still owns an asset is refused by the constraint alone, with the generic
    // foreign-key message and no indication of the actual cause. Every asset type answers for
    // itself through AssetOwnershipDirectory, so a further type extends this check by adding a
    // bean.
    for (AssetOwnershipDirectory ownership : assetOwnershipDirectories) {
      if (ownership.existsAssetOwnedByGroup(groupId)) {
        throw new ConflictException(ownership.ownedAssetConflictMessage(groupId));
      }
    }
    // A group that merely holds a grant (never owns anything) hits the same RESTRICT constraint
    // via fk_asset_grants_subject_group_organization - see the class Javadoc.
    if (grantRepository.existsBySubjectGroupId(groupId)) {
      throw new ConflictException(
          "Die Gruppe hat noch Berechtigungen auf Bibliotheken und kann nicht gelöscht werden");
    }
    // The same RESTRICT pattern one table further
    // (fk_capability_grants_subject_group_organization): without this check the deletion is still
    // refused, but with the generic foreign-key message instead of the reason.
    if (capabilityGrantRepository.existsBySubjectGroupId(groupId)) {
      throw new ConflictException(
          "Die Gruppe hat noch Anlegerechte und kann nicht gelöscht werden");
    }
    // Same class of RESTRICT reference on the space axis since #1815
    // (fk_space_memberships_group_organization) - without this the deletion would surface as an
    // opaque 500 instead of naming the spaces that are in the way.
    int spaceMemberships = spaceMemberships(groupId);
    if (spaceMemberships > 0) {
      throw new ConflictException(
          "Die Gruppe ist noch Mitglied von "
              + (spaceMemberships == 1 ? "1 Space" : spaceMemberships + " Spaces")
              + " und kann nicht gelöscht werden");
    }

    List<UUID> affectedUserIds =
        group.getMemberships().stream().map(GroupMembership::getUserId).toList();
    // group_id carries no foreign key on group_membership_history (deliberately - see
    // PermissionHistoryService's class Javadoc), so the CASCADE delete below
    // (fk_group_memberships_group_organization) never closes these intervals on its own. Without
    // this, a deleted group's still-open membership intervals kept reporting "currently a member"
    // of a group that no longer exists. Read the live memberships before the delete cascades them
    // away - deleteGroup is only reachable once the guards above confirm no live grant remains, so
    // there is nothing to close on the asset_grant_history side.
    for (GroupMembership membership : group.getMemberships()) {
      permissionHistoryService.recordMembershipRemoved(
          group.getId(),
          group.getOrganizationId(),
          membership.getUserId(),
          GroupMembershipHistoryCause.GROUP_DELETED,
          caller.id());
    }
    // #392: GROUP_DELETED also covers the group's dissolution ("Auflösung einer Gruppe") - one
    // entry for the group itself, not one per member removed above (those are already covered by
    // the group's own deletion, not a separate membership-removal action).
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(group.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.GROUP_DELETED)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .before(Map.of("name", group.getName(), "memberCount", affectedUserIds.size()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    groupRepository.delete(group);
    invalidateAfterCommit(() -> membershipResolver.invalidateUsers(affectedUserIds));
  }

  /** How many spaces this group is a member of - the RESTRICT reference #1815 introduced. */
  private int spaceMemberships(UUID groupId) {
    return spaceMembershipDirectory.spaceMembershipsOf(List.of(groupId)).size();
  }

  /**
   * A group's members. For a system administrator who stewards none of it, reading this list is
   * itself an event (ADR-0036, Entscheidung 9: "der Abruf ist ein Audit-Ereignis"; Personalrat A6)
   * - the administration may see who is in a group, and that it looked is on the record. A steward
   * reading the list they maintain writes nothing: it is their own group.
   */
  @Transactional
  public List<GroupMemberView> listMembers(UUID groupId, CurrentUser caller) {
    Group group = requireMaintainable(groupId, caller);
    List<GroupMemberView> members = toGroupMemberViews(group);
    if (readsAsAdministration(groupId, caller)) {
      auditEventRecorder.recordUserAction(
          AuditEvent.builder()
              .organizationId(group.getOrganizationId())
              .actor(caller.id())
              .type(AuditEventType.GROUP_MEMBERS_READ)
              .object(AuditObjectType.GROUP, group.getId(), group.getName())
              .after(Map.of("memberCount", members.size()))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    return members;
  }

  @Transactional
  public GroupMemberView addMember(UUID groupId, UUID memberUserId, CurrentUser caller) {
    Group group = requireMaintainable(groupId, caller);
    rejectOrgUnit(group);
    // Resolving the target user first also turns a non-existent userId into a clean 404 instead
    // of a raw foreign-key violation from the membership insert below.
    requireUserInOrganization(memberUserId, group.getOrganizationId());

    if (userMembership(group, memberUserId) != null) {
      throw new ConflictException("Der Benutzer ist bereits Mitglied dieser Gruppe");
    }

    GroupMembership membership = new GroupMembership(memberUserId, group.getOrganizationId());
    group.addMembership(membership);
    groupRepository.save(group);
    permissionHistoryService.recordMembershipAdded(
        group.getId(),
        group.getOrganizationId(),
        memberUserId,
        GroupMembershipHistoryCause.ADDED,
        caller.id());
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(group.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.GROUP_MEMBER_ADDED)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .subject(AuditSubjectKind.USER, memberUserId)
            .outcome(AuditOutcome.SUCCESS)
            .build());
    notifyMembershipChange(group, memberUserId, true);
    invalidateAfterCommit(() -> membershipResolver.invalidateUser(memberUserId));

    return new GroupMemberView(membership, resolveDisplayName(membership.getUserId()));
  }

  @Transactional
  public void removeMember(UUID groupId, UUID memberUserId, CurrentUser caller) {
    Group group = requireMaintainable(groupId, caller);
    rejectOrgUnit(group);

    GroupMembership target = userMembership(group, memberUserId);
    if (target == null) {
      throw new NotFoundException("Mitglied der Gruppe nicht gefunden");
    }

    group.removeMembership(target);
    groupRepository.save(group);
    permissionHistoryService.recordMembershipRemoved(
        group.getId(),
        group.getOrganizationId(),
        memberUserId,
        GroupMembershipHistoryCause.REMOVED,
        caller.id());
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(group.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.GROUP_MEMBER_REMOVED)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .subject(AuditSubjectKind.USER, memberUserId)
            .outcome(AuditOutcome.SUCCESS)
            .build());
    notifyMembershipChange(group, memberUserId, false);
    invalidateAfterCommit(() -> membershipResolver.invalidateUser(memberUserId));
  }

  /**
   * Tells the person concerned that their membership changed - in the application, never by mail
   * (ADR-0036, Entscheidung 4; Personalrat A4). Without it a read right can end "immediately"
   * without the person learning that it did, or through whom.
   */
  private void notifyMembershipChange(Group group, UUID memberUserId, boolean added) {
    notificationService.notify(
        group.getOrganizationId(),
        memberUserId,
        added ? NotificationType.GROUP_MEMBER_ADDED : NotificationType.GROUP_MEMBER_REMOVED,
        AuditObjectType.GROUP,
        group.getId(),
        added
            ? "Sie wurden in die Gruppe „" + group.getName() + "“ aufgenommen"
            : "Sie wurden aus der Gruppe „" + group.getName() + "“ entfernt",
        added
            ? "Über diese Gruppe können Ihnen Rechte auf Bibliotheken und Spaces zuwachsen."
            : "Rechte, die Ihnen über diese Gruppe zugewachsen waren, enden damit.");
  }

  private String validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new ValidationException("name ist erforderlich");
    }
    String trimmed = name.trim();
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new ValidationException("name darf höchstens " + MAX_NAME_LENGTH + " Zeichen umfassen");
    }
    return trimmed;
  }

  private void validateDescription(String description) {
    if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new ValidationException(
          "description darf höchstens " + MAX_DESCRIPTION_LENGTH + " Zeichen umfassen");
    }
  }

  /**
   * Defers a cache invalidation until the enclosing transaction has finished, instead of running it
   * immediately at the point of the call. Every {@code @Transactional} method here commits through
   * the Spring proxy only after it returns, so invalidating inline (as an earlier version of this
   * class did) can race a concurrent reader: it can observe the pre-image under {@code READ
   * COMMITTED}, repopulate the cache with it, and then have this transaction commit - leaving a
   * revoked membership readable from the cache for up to the cache's expiry (see {@link
   * GroupMembershipResolver}).
   *
   * <p>Registered as {@code afterCompletion} rather than {@code afterCommit} so a rollback also
   * evicts the entry the transaction may have touched - a stale hit is the wrong failure mode
   * either way, so there is no reason to skip cleanup on the rollback path.
   *
   * <p>Falls back to running immediately when no transaction is active (e.g. called directly in a
   * test), so the invalidation is never silently dropped.
   */
  private void invalidateAfterCommit(Runnable invalidation) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      invalidation.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            invalidation.run();
          }
        });
  }

  /** Only {@link GroupKind#AD_HOC} groups are managed here; the other kinds have their source. */
  private void rejectOrgUnit(Group group) {
    if (group.isOrgUnit()) {
      throw new ValidationException(
          "Organisationseinheiten werden aus dem Verzeichnis synchronisiert und können hier"
              + " nicht bearbeitet werden");
    }
    if (group.getKind() == GroupKind.IDENTITY_PROVIDER) {
      throw new ValidationException(
          "Diese Gruppe stammt aus dem Identitätsanbieter und wird bei jeder Anmeldung abgeglichen;"
              + " sie kann hier nicht bearbeitet werden");
    }
  }

  /**
   * Resolves a user and enforces the organization boundary for it via {@link
   * OrganizationScopedLoader} - mirrors {@code SpaceService#requireUserInOrganization}. Returns 404
   * rather than 403 both when the user does not exist and when it belongs to a different
   * organization, so a caller cannot distinguish "no such user" from "user in another
   * organization".
   */
  private User requireUserInOrganization(UUID userId, UUID organizationId) {
    return OrganizationScopedLoader.load(
        () -> userRepository.findById(userId),
        User::getOrganizationId,
        organizationId,
        "Benutzer nicht gefunden");
  }

  /**
   * Loads a group the caller may maintain: one of its stewards, or a system administrator. Anybody
   * else gets the answer an unknown group gets - a {@code 403} would confirm that a group with this
   * id exists (ADR-0036, Entscheidung 4), the same reasoning {@link #loadGroup} applies to the
   * organization boundary.
   */
  private Group requireMaintainable(UUID groupId, CurrentUser caller) {
    Group group = loadGroup(groupId, caller);
    if (caller.isSystemAdmin()
        || stewardRepository.existsByGroupIdAndUserId(groupId, caller.id())) {
      return group;
    }
    throw new NotFoundException("Gruppe nicht gefunden");
  }

  /** Writes the stewardship row and its audit event - the one path an appointment takes. */
  private GroupSteward appoint(Group group, UUID userId, CurrentUser caller) {
    GroupSteward steward =
        stewardRepository.save(
            new GroupSteward(group.getId(), userId, group.getOrganizationId(), caller.id()));
    recordStewardshipEvent(AuditEventType.GROUP_STEWARD_APPOINTED, group, userId, caller);
    return steward;
  }

  /**
   * Appointment and dismissal are audit events and deliberately no history rows (ADR-0036,
   * Entscheidungen 4 and 8): responsibility carries no read access, so it says nothing about who
   * could read what on a given day.
   */
  private void recordStewardshipEvent(
      AuditEventType type, Group group, UUID userId, CurrentUser caller) {
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(group.getOrganizationId())
            .actor(caller.id())
            .type(type)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .subject(AuditSubjectKind.USER, userId)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /**
   * Release and protection decide how far a group reaches and who sees it, so each change is its
   * own event with the value before and after - the treatment {@code
   * LIBRARY_DIAGNOSTICS_LOCK_CHANGED} gets for the same reason.
   */
  private void recordReachEvent(
      AuditEventType type, Group group, String field, CurrentUser caller) {
    boolean after = "protected".equals(field) ? group.isProtectedGroup() : group.isReleasedForUse();
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(group.getOrganizationId())
            .actor(caller.id())
            .type(type)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .before(Map.of(field, !after))
            .after(Map.of(field, after))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private List<GroupSteward> stewardsOf(UUID groupId) {
    return stewardRepository.findByGroupIdOrderByCreatedAtAsc(groupId);
  }

  private List<GroupStewardView> toStewardViews(List<GroupSteward> stewards) {
    Map<UUID, String> displayNames =
        resolveDisplayNames(stewards.stream().map(GroupSteward::getUserId).toList());
    return stewards.stream()
        .map(steward -> new GroupStewardView(steward, displayNames.get(steward.getUserId())))
        .toList();
  }

  /**
   * Loads a group and enforces the organization boundary via {@link OrganizationScopedLoader},
   * treating a group from another organization as not found. Applies to system admins as well; the
   * boundary is not overstepped even to reveal existence.
   */
  private Group loadGroup(UUID groupId, CurrentUser caller) {
    return OrganizationScopedLoader.load(
        () -> groupRepository.findByIdWithMemberships(groupId),
        Group::getOrganizationId,
        caller.organizationId(),
        "Gruppe nicht gefunden");
  }

  private GroupMembership userMembership(Group group, UUID userId) {
    return group.getMemberships().stream()
        .filter(membership -> membership.getUserId().equals(userId))
        .findFirst()
        .orElse(null);
  }

  private String resolveDisplayName(UUID userId) {
    return userRepository
        .findById(userId)
        .map(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail())
        .orElse(null);
  }

  private Map<UUID, String> resolveDisplayNames(List<UUID> userIds) {
    return userRepository.displayNamesById(userIds);
  }

  private List<GroupMemberView> toGroupMemberViews(Group group) {
    List<UUID> memberIds = group.getMemberships().stream().map(GroupMembership::getUserId).toList();
    Map<UUID, String> displayNames = resolveDisplayNames(memberIds);

    return group.getMemberships().stream()
        .map(m -> new GroupMemberView(m, displayNames.get(m.getUserId())))
        .toList();
  }

  private boolean readsAsAdministration(UUID groupId, CurrentUser caller) {
    return AdministrationReads.readsAsAdministration(stewardRepository, groupId, caller);
  }

  /**
   * The detail every read and write path answers with. Its member list is withheld ({@code null})
   * from a caller reading as the administration, so no answer bypasses the recorded {@link
   * #listMembers}.
   */
  private GroupDetail toGroupDetail(Group group, CurrentUser caller) {
    return new GroupDetail(
        group,
        readsAsAdministration(group.getId(), caller) ? null : toGroupMemberViews(group),
        toStewardViews(stewardsOf(group.getId())),
        providerOf(group));
  }
}
