package io.opaa.asset;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetType;
import io.opaa.permission.GroupAttribution;
import io.opaa.permission.GroupMemberDisclosure;
import io.opaa.permission.GroupMemberDisclosureDirectory;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSizeProperties;
import io.opaa.permission.GroupSizeSignal;
import io.opaa.permission.GroupSubject;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.SuccessionReachGuard;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manages {@link AssetGrant}s on an asset of any type - the "who has which {@link AssetRole}" side
 * of the asset shell. The only grant service: no asset type brings its own. Every mutating method
 * requires {@link AssetRole#MANAGER} on the target asset, resolved through {@link
 * AssetAuthorization}.
 *
 * <p><b>Escalation guards, both directions:</b> a caller may never act on a role higher than their
 * own - neither grant it ({@link #upsertGrant}) nor change or remove an existing grant that carries
 * it ({@link #upsertGrant}'s update path, {@link #revokeGrant}). {@link #requireCallerRoleAtLeast}
 * enforces {@code callerRole >= otherRole} at each of these places.
 *
 * <p><b>The last active {@code OWNER} grant stays.</b> Removing or downgrading it - counted
 * <em>after</em> the intended change, including a new {@code expiresAt} - would leave nobody able
 * to manage the asset. The count runs under a per-asset advisory lock ({@link
 * AssetGrantRepository#lockAssetGrantsForMutation}) and as a plain scalar read ({@link
 * AssetGrantRepository#countOtherActiveOwnerGrants}), which is both deadlock- and staleness-safe.
 *
 * <p>No new or widened grant targets a group that is no effective grant target any more (dissolved,
 * provider disabled, unmaintained token group); existing grants to it keep working.
 *
 * <p>Every response carries {@code subjectDisplayName} and {@code grantedByDisplayName}, resolved
 * here - see {@link #toViews(List)}.
 */
@Service
@Transactional(readOnly = true)
public class AssetGrantService {

  private final AssetGrantRepository grantRepository;
  private final UserRepository userRepository;
  private final GroupSubjectDirectory groupDirectory;
  private final GroupMemberDisclosureDirectory disclosureDirectory;
  private final GroupMembershipResolver groupMemberships;
  private final GroupSizeProperties groupSizeProperties;
  private final AssetAuthorization authorization;
  private final AssetAccessService accessService;
  private final AssetTypes assetTypes;
  private final AuditEventRecorder auditEventRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final SuccessionReachGuard successionGuard;

  AssetGrantService(
      AssetGrantRepository grantRepository,
      UserRepository userRepository,
      GroupSubjectDirectory groupDirectory,
      GroupMemberDisclosureDirectory disclosureDirectory,
      GroupMembershipResolver groupMemberships,
      GroupSizeProperties groupSizeProperties,
      AssetAuthorization authorization,
      AssetAccessService accessService,
      AssetTypes assetTypes,
      AuditEventRecorder auditEventRecorder,
      ApplicationEventPublisher eventPublisher,
      SuccessionReachGuard successionGuard) {
    this.grantRepository = grantRepository;
    this.userRepository = userRepository;
    this.groupDirectory = groupDirectory;
    this.disclosureDirectory = disclosureDirectory;
    this.groupMemberships = groupMemberships;
    this.groupSizeProperties = groupSizeProperties;
    this.authorization = authorization;
    this.accessService = accessService;
    this.assetTypes = assetTypes;
    this.auditEventRecorder = auditEventRecorder;
    this.eventPublisher = eventPublisher;
    this.successionGuard = successionGuard;
  }

  public List<AssetGrantView> listGrants(AssetType assetType, UUID assetId, CurrentUser caller) {
    Asset asset = requireManageable(assetType, assetId, caller);
    return toViews(grantRepository.findByAssetTypeAndAssetId(assetType, asset.getId()));
  }

  /**
   * The members of a group that holds a grant on this asset, under the rule of {@link
   * GroupMemberDisclosureDirectory} (#1880). This method carries what is its own: the {@code
   * MANAGER} bar of {@link #requireManageable} and limit (a) - only while the group holds an
   * <b>unexpired</b> grant here. Write-transactional because a retrieval carried by the system role
   * writes an audit event.
   */
  @Transactional
  public GroupMemberDisclosure listGroupMembers(
      AssetType assetType, UUID assetId, UUID groupId, int offset, int limit, CurrentUser caller) {
    Asset asset = requireManageable(assetType, assetId, caller);
    AssetGrant grant =
        grantRepository
            .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
                assetType, asset.getId(), PermissionSubjectType.GROUP, groupId)
            .filter(found -> !found.isExpired(Instant.now()))
            .orElseThrow(() -> new NotFoundException("Gruppe nicht gefunden"));
    return disclosureDirectory
        .disclose(grant.getSubjectId(), asset.getOrganizationId(), caller, offset, limit)
        .orElseThrow(() -> new NotFoundException("Gruppe nicht gefunden"));
  }

  // noRollbackFor(AccessDeniedException): the DENIED audit entry the escalation guard writes below
  // must survive the exception it rethrows. Safe, because every exception this method throws fires
  // before grantRepository.save - nothing but that audit entry is ever written on such a path.
  @Transactional(noRollbackFor = AccessDeniedException.class)
  public AssetGrantView upsertGrant(
      AssetType assetType, UUID assetId, AssetGrantUpsert request, CurrentUser caller) {
    UUID currentUserId = caller.id();
    Asset asset = requireManageable(assetType, assetId, caller);
    AssetTypeDefinition definition = assetTypes.require(assetType);

    if (request.subjectType() == null || request.subjectId() == null) {
      throw new ValidationException("Empfänger ist erforderlich");
    }
    if (request.role() == null) {
      throw new ValidationException("Rolle ist erforderlich");
    }
    // Subject validation runs before the escalation guard: the guard pseudonymises the subject for
    // its DENIED entry, and a subject that names no real account of this organization would violate
    // the pseudonym table's foreign key - an unknown or foreign subject is a plain 404 instead.
    if (request.subjectType() == PermissionSubjectType.USER) {
      requireUserInOrganization(request.subjectId(), asset.getOrganizationId());
    } else {
      requireGrantableGroup(request.subjectId(), asset.getOrganizationId(), caller);
    }

    // Escalation guard, half 1: a caller may never grant a role higher than their own.
    AssetRole callerRole =
        authorization.effectiveRole(asset, currentUserId, caller.isSystemAdmin());
    try {
      requireCallerRoleAtLeast(
          callerRole,
          request.role(),
          "Die eigene Rolle reicht nicht aus, um die Rolle "
              + roleLabel(request.role())
              + " zu vergeben");
    } catch (AccessDeniedException denied) {
      // The rejected attempt to grant oneself a higher role is itself protocol-worthy
      // (docs/features/security-and-compliance.md).
      auditEventRecorder.recordUserActionOnSubject(
          AuditEvent.builder()
              .organizationId(asset.getOrganizationId())
              .actor(currentUserId)
              .type(AuditEventType.ASSET_GRANT_GRANTED)
              .object(definition.auditObjectType(), asset.getId(), asset.getName())
              .subject(
                  request.subjectType() == PermissionSubjectType.USER
                      ? AuditSubjectKind.USER
                      : AuditSubjectKind.GROUP,
                  request.subjectId())
              .after(Map.of("role", request.role().name()))
              .outcome(AuditOutcome.DENIED)
              .reason(denied.getMessage())
              .build());
      throw denied;
    }

    // One reference instant for the whole upsert, so the revival check in AssetGrant#updateRole and
    // any expiry comparison below judge the same moment.
    Instant now = Instant.now();
    AssetGrant grant =
        (request.subjectType() == PermissionSubjectType.USER
                ? grantRepository.findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectUserId(
                    assetType, asset.getId(), PermissionSubjectType.USER, request.subjectId())
                : grantRepository.findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
                    assetType, asset.getId(), PermissionSubjectType.GROUP, request.subjectId()))
            .orElse(null);
    boolean isNewGrant = grant == null;
    requireReachNotFrozenIfWidening(asset, grant, request);
    // The "before" half of an ASSET_GRANT_CHANGED entry, captured before updateRole mutates it.
    AssetRole previousRole = null;
    Instant previousExpiresAt = null;
    if (isNewGrant) {
      grant =
          request.subjectType() == PermissionSubjectType.USER
              ? AssetGrant.forUser(
                  assetType,
                  asset.getId(),
                  asset.getOrganizationId(),
                  request.subjectId(),
                  request.role(),
                  request.expiresAt(),
                  currentUserId)
              : AssetGrant.forGroup(
                  assetType,
                  asset.getId(),
                  asset.getOrganizationId(),
                  request.subjectId(),
                  request.role(),
                  request.expiresAt(),
                  currentUserId,
                  groupMemberships.activeMemberCount(
                      request.subjectId(), asset.getOrganizationId()));
    } else {
      requireCallerCanTouchExistingGrant(callerRole, grant, "ändern");
      requireNotDowngradingTheLastActiveOwnerGrant(
          asset, definition, grant, request.role(), request.expiresAt());
      previousRole = grant.getRole();
      previousExpiresAt = grant.getExpiresAt();
      grant.updateRole(request.role(), request.expiresAt(), currentUserId, now);
    }

    AssetGrant saved = grantRepository.save(grant);
    eventPublisher.publishEvent(
        isNewGrant
            ? new AssetGrantChanged(
                asset,
                saved,
                AssetGrantChanged.Cause.GRANTED,
                currentUserId,
                null,
                grantAuditPayload(saved.getRole(), saved.getExpiresAt()))
            : new AssetGrantChanged(
                asset,
                saved,
                AssetGrantChanged.Cause.ROLE_CHANGED,
                currentUserId,
                grantAuditPayload(previousRole, previousExpiresAt),
                grantAuditPayload(saved.getRole(), saved.getExpiresAt())));
    invalidateAfterCommit(assetType, asset.getId());
    return toViews(List.of(saved)).get(0);
  }

  /**
   * Grants the role that goes with creating or owning an asset, without the manager checks of
   * {@link #upsertGrant} - the caller is the shell itself, at the asset's creation. A group owner's
   * grant carries no growth signal: it is ownership, not a release.
   */
  AssetGrant grantAtCreation(Asset asset, AssetGrant grant, UUID actorUserId) {
    AssetGrant saved = grantRepository.save(grant);
    eventPublisher.publishEvent(
        new AssetGrantChanged(
            asset,
            saved,
            AssetGrantChanged.Cause.GRANTED,
            actorUserId,
            null,
            Map.of("role", saved.getRole().name())));
    invalidateAfterCommit(asset.getAssetType(), asset.getId());
    return saved;
  }

  /**
   * ADR-0036, Entscheidung 6: while an asset's succession is open its reach is frozen - a new
   * grant, a higher role or a postponed expiry are refused. Taking reach away is not: a downgrade,
   * an expiry brought forward and a revocation stay open to whoever is still there.
   */
  private void requireReachNotFrozenIfWidening(
      Asset asset, AssetGrant existing, AssetGrantUpsert request) {
    if (existing != null && !widensReach(existing, request.role(), request.expiresAt())) {
      return;
    }
    successionGuard.requireAssetReachNotFrozen(
        asset.getAssetType(),
        asset.getId(),
        existing == null ? "Eine neue Berechtigung" : "Eine größere Berechtigung");
  }

  private static boolean widensReach(
      AssetGrant existing, AssetRole requestedRole, Instant requestedExpiresAt) {
    if (requestedRole.atLeast(existing.getRole()) && requestedRole != existing.getRole()) {
      return true;
    }
    Instant previousExpiry = existing.getExpiresAt();
    if (previousExpiry == null) {
      // Unlimited already: no expiry can reach further.
      return false;
    }
    return requestedExpiresAt == null || requestedExpiresAt.isAfter(previousExpiry);
  }

  private Map<String, Object> grantAuditPayload(AssetRole role, Instant expiresAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("role", role.name());
    if (expiresAt != null) {
      payload.put("expiresAt", expiresAt.toString());
    }
    return payload;
  }

  @Transactional
  public void revokeGrant(AssetType assetType, UUID assetId, UUID grantId, CurrentUser caller) {
    UUID currentUserId = caller.id();
    Asset asset = requireManageable(assetType, assetId, caller);
    AssetTypeDefinition definition = assetTypes.require(assetType);
    AssetGrant grant =
        grantRepository
            .findById(grantId)
            .orElseThrow(() -> new NotFoundException("Berechtigung nicht gefunden"));
    if (!grant.getAssetId().equals(asset.getId()) || !grant.getAssetType().equals(assetType)) {
      throw new NotFoundException("Berechtigung nicht gefunden");
    }
    // Escalation guard, half 2: a caller may never touch a grant that carries a role higher than
    // their own, regardless of whether they could have granted that role.
    AssetRole callerRole =
        authorization.effectiveRole(asset, currentUserId, caller.isSystemAdmin());
    requireCallerCanTouchExistingGrant(callerRole, grant, "entfernen");

    if (grant.getRole() == AssetRole.OWNER
        && !grant.isExpired(Instant.now())
        && isLastActiveOwnerGrant(assetType, asset.getId(), grant.getId())) {
      throw new ConflictException(
          "Die letzte "
              + roleLabel(AssetRole.OWNER)
              + "-Berechtigung einer "
              + definition.singular()
              + " kann nicht entfernt werden");
    }

    // Published before the row is gone - both listeners read the grant's last-active role and
    // expiry off this same entity.
    eventPublisher.publishEvent(
        new AssetGrantChanged(
            asset,
            grant,
            AssetGrantChanged.Cause.REVOKED,
            currentUserId,
            grantAuditPayload(grant.getRole(), grant.getExpiresAt()),
            null));
    grantRepository.delete(grant);
    invalidateAfterCommit(assetType, asset.getId());
  }

  private void requireCallerCanTouchExistingGrant(
      AssetRole callerRole, AssetGrant existingGrant, String action) {
    requireCallerRoleAtLeast(
        callerRole,
        existingGrant.getRole(),
        "Die eigene Rolle reicht nicht aus, um eine bestehende "
            + roleLabel(existingGrant.getRole())
            + "-Berechtigung zu "
            + action);
  }

  /**
   * The German role label of user-facing messages, analogous to {@code assetRoleLabel} in {@code
   * frontend/src/utils/labels.ts}. Every {@link AssetRole} value must be mapped here, or the raw
   * English enum name would leak into a German message.
   */
  private static String roleLabel(AssetRole role) {
    return switch (role) {
      case VIEWER -> "Betrachter";
      case EDITOR -> "Bearbeiter";
      case MANAGER -> "Verwalter";
      case OWNER -> "Eigentümer";
    };
  }

  /** Throws {@code 403} unless {@code callerRole} is at least {@code otherRole}. */
  private void requireCallerRoleAtLeast(AssetRole callerRole, AssetRole otherRole, String message) {
    if (callerRole == null || otherRole.ordinal() > callerRole.ordinal()) {
      throw new AccessDeniedException(message);
    }
  }

  /**
   * Whether {@code excludingGrantId} is the asset's only active {@code OWNER} grant. Takes the
   * per-asset advisory lock first, then counts with a plain scalar read.
   */
  private boolean isLastActiveOwnerGrant(AssetType assetType, UUID assetId, UUID excludingGrantId) {
    grantRepository.lockAssetGrantsForMutation(assetType.value(), assetId);
    return grantRepository.countOtherActiveOwnerGrants(
            assetType.value(), assetId, excludingGrantId, Instant.now())
        == 0;
  }

  /**
   * The revocation guard applied to {@link #upsertGrant}'s update path: lowering the last active
   * {@code OWNER} grant is as dangerous as revoking it. Counted <em>after</em> the intended change
   * - only {@code OWNER} with an absent or future {@code expiresAt} stays an active owner.
   */
  private void requireNotDowngradingTheLastActiveOwnerGrant(
      Asset asset,
      AssetTypeDefinition definition,
      AssetGrant existingGrant,
      AssetRole newRole,
      Instant newExpiresAt) {
    Instant now = Instant.now();
    if (existingGrant.getRole() != AssetRole.OWNER || existingGrant.isExpired(now)) {
      return;
    }
    boolean staysActiveOwner =
        newRole == AssetRole.OWNER && (newExpiresAt == null || newExpiresAt.isAfter(now));
    if (staysActiveOwner) {
      return;
    }
    if (isLastActiveOwnerGrant(asset.getAssetType(), asset.getId(), existingGrant.getId())) {
      throw new ConflictException(
          "Die letzte "
              + roleLabel(AssetRole.OWNER)
              + "-Berechtigung einer "
              + definition.singular()
              + " kann nicht herabgestuft werden");
    }
  }

  private Asset requireManageable(AssetType assetType, UUID assetId, CurrentUser caller) {
    Asset asset = authorization.load(assetType, assetId, caller.organizationId());
    authorization.requireRole(asset, caller.id(), caller.isSystemAdmin(), AssetRole.MANAGER);
    return asset;
  }

  private void requireUserInOrganization(UUID userId, UUID organizationId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new NotFoundException("Benutzer nicht gefunden"));
    if (!user.getOrganizationId().equals(organizationId)) {
      throw new NotFoundException("Benutzer nicht gefunden");
    }
  }

  /**
   * Requires that a new asset of {@code assetType} may be created in the name of {@code groupId}:
   * the group is in the caller's organization ({@code 404} otherwise), the caller is one of its
   * members ({@code 403}), and it may receive the owning group's {@code MANAGER} grant ({@link
   * #requireGrantableGroup}).
   */
  public void requireOwnableGroup(UUID groupId, AssetType assetType, CurrentUser caller) {
    GroupSubject group =
        groupDirectory
            .find(groupId)
            .filter(found -> found.organizationId().equals(caller.organizationId()))
            .orElseThrow(() -> new NotFoundException("Gruppe nicht gefunden"));
    if (!groupMemberships.groupIdsForUser(caller.id()).contains(group.id())) {
      throw new AccessDeniedException(
          "Nur Mitglieder der Gruppe können eine "
              + assetTypes.require(assetType).singular()
              + " in ihrem Namen anlegen");
    }
    requireGrantableGroup(group.id(), caller.organizationId(), caller);
  }

  /**
   * Resolves a group, enforces the organization boundary, and rejects a group that is no effective
   * grant target - dissolved, belonging to a disabled identity provider (ADR-0036, Entscheidung 2),
   * or a token group its provider no longer maintains (Entscheidung 3). Existing grants to such a
   * group keep working; no new or updated grant may target it.
   *
   * <p>Takes the caller because the visibility of an internal group depends on them (ADR-0036,
   * Entscheidung 9): one its stewards have not released is "not found" for anybody who is neither
   * its member, nor one of its stewards, nor a system administrator - on every path.
   */
  public void requireGrantableGroup(UUID groupId, UUID organizationId, CurrentUser caller) {
    GroupSubject group =
        groupDirectory
            .find(groupId)
            .orElseThrow(() -> new NotFoundException("Gruppe nicht gefunden"));
    if (!group.organizationId().equals(organizationId)) {
      throw new NotFoundException("Gruppe nicht gefunden");
    }
    if (!groupDirectory.isSelectableBy(groupId, caller.id(), caller.isSystemAdmin())) {
      throw new NotFoundException("Gruppe nicht gefunden");
    }
    if (group.dissolved()) {
      throw new ValidationException(
          "Die Gruppe ist aufgelöst und kann keine neuen Berechtigungen mehr erhalten");
    }
    if (group.providerDisabled()) {
      throw new ValidationException(
          "Der Identitätsanbieter dieser Gruppe ist deaktiviert. Sie kann keine neuen"
              + " Berechtigungen erhalten, solange er es bleibt; bestehende Berechtigungen"
              + " bleiben unverändert.");
    }
    if (group.unmaintained()) {
      throw new ValidationException(
          "Diese Gruppe stammt aus dem Gruppen-Claim eines Anbieters, der inzwischen über den"
              + " Verzeichnisabgleich gepflegt wird. Ihre Mitgliedschaft ist eingefroren, deshalb"
              + " kann sie keine neuen Berechtigungen mehr erhalten; bestehende bleiben"
              + " unverändert. Vergeben Sie das Recht an die entsprechende"
              + " Organisationseinheit.");
    }
  }

  /**
   * Defers cache invalidation until the enclosing transaction has finished ({@code
   * afterCompletion}, so a rollback also evicts an entry this transaction may have touched). Runs
   * immediately when no transaction is active.
   */
  void invalidateAfterCommit(AssetType assetType, UUID assetId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      accessService.invalidateAsset(assetType, assetId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            accessService.invalidateAsset(assetType, assetId);
          }
        });
  }

  /**
   * Resolves {@code subjectDisplayName} and {@code grantedByDisplayName} server-side: the
   * administration's own user and group lists require {@code SYSTEM_ADMIN}, exactly the role this
   * endpoint's {@code MANAGER} threshold admits without. Two batched lookups for the whole list,
   * not one per grant.
   */
  private List<AssetGrantView> toViews(List<AssetGrant> grants) {
    Set<UUID> userIds = new HashSet<>();
    Set<UUID> groupIds = new HashSet<>();
    for (AssetGrant grant : grants) {
      if (grant.getSubjectType() == PermissionSubjectType.USER) {
        userIds.add(grant.getSubjectId());
      } else {
        groupIds.add(grant.getSubjectId());
      }
      if (grant.getGrantedByUserId() != null) {
        userIds.add(grant.getGrantedByUserId());
      }
    }
    Map<UUID, String> userNames = new HashMap<>();
    for (User user : userRepository.findAllById(userIds)) {
      // displayName is nullable (a token without a name claim leaves it unset); email is always
      // present on a persisted user and is the last resort before the raw id.
      String name = user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
      userNames.put(user.getId(), name);
    }
    Map<UUID, GroupAttribution> groups = groupDirectory.attributionsById(groupIds);

    return grants.stream()
        .map(
            grant -> {
              String grantedByName =
                  grant.getGrantedByUserId() == null
                      ? null
                      : userNames.get(grant.getGrantedByUserId());
              if (grant.getSubjectType() == PermissionSubjectType.USER) {
                return AssetGrantView.ofUser(
                    grant, userNames.get(grant.getSubjectId()), grantedByName);
              }
              GroupAttribution group = groups.get(grant.getSubjectId());
              boolean protectedGroup = group != null && group.protectedGroup();
              return AssetGrantView.ofGroup(
                  grant,
                  group == null ? null : group.name(),
                  grantedByName,
                  protectedGroup,
                  protectedGroup ? GroupSizeSignal.NONE : groupSizeSignal(grant));
            })
        .toList();
  }

  /**
   * The growth signal of a group grant (ADR-0036, Entscheidung 9) - see {@link GroupSizeSignal}.
   */
  private GroupSizeSignal groupSizeSignal(AssetGrant grant) {
    return GroupSizeSignal.of(
        grant.getMemberCountAtGrant(),
        groupMemberships.activeMemberCount(grant.getSubjectId(), grant.getOrganizationId()),
        groupSizeProperties.minimumGroupSize());
  }
}
