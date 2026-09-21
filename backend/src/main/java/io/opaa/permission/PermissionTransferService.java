package io.opaa.permission;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.CapabilitySubjectType;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.PermissionTransferScope;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Moves every effect one subject has onto another in a single, recorded operation (#1834, ADR-0036
 * Entscheidung 10) - the one mechanic behind reorganisation, provider replacement, a change of
 * group mechanism and succession.
 *
 * <p><b>The preview is mandatory and is itself an event</b>, also when the caller then abandons it;
 * the execution needs an explicit confirmation. Every moved row closes the source's interval and
 * opens the target's at <b>one</b> boundary instant carrying <b>one</b> transfer id, so the
 * Stichtagsauskunft shows exactly one subject per object and day.
 *
 * <p><b>A person as the source is limited to ownership and stewardship.</b> Grants and space
 * memberships of a person are neither transferable nor listable here: the preview would otherwise
 * be an "every effect of person X" query for the administration, without authorisation and without
 * a reason.
 *
 * <p><b>A transfer never lowers what the target already holds.</b> Where both sides meet on the
 * same object, the stronger role stays and only the source's row goes; the target then gets no new
 * interval, because its state did not change.
 */
@Service
@Transactional(readOnly = true)
public class PermissionTransferService {

  /** The parts only a group can hand to a group - see the class Javadoc. */
  private static final Set<PermissionTransferScope> GROUP_TO_GROUP_ONLY =
      EnumSet.of(
          PermissionTransferScope.ASSET_GRANTS,
          PermissionTransferScope.SPACE_MEMBERSHIPS,
          PermissionTransferScope.CAPABILITIES);

  /** What a person may hand over without being a system administrator - their own load. */
  private static final Set<PermissionTransferScope> SELF_SERVICE =
      EnumSet.of(PermissionTransferScope.OWNERSHIP, PermissionTransferScope.STEWARDSHIP);

  private final PermissionTransferRepository transferRepository;
  private final PermissionTransferObjectRepository transferObjectRepository;
  private final AssetGrantRepository grantRepository;
  private final CapabilityGrantRepository capabilityGrantRepository;
  private final GroupSubjectDirectory groupDirectory;
  private final GroupSpaceMembershipDirectory spaceMembershipDirectory;
  private final GroupStewardshipDirectory stewardshipDirectory;
  private final List<AssetOwnershipDirectory> assetOwnershipDirectories;
  private final PermissionHistoryService permissionHistoryService;
  private final PermissionHistoryClock clock;
  private final AssetAccessService assetAccessService;
  private final UserRepository userRepository;
  private final AuditEventRecorder auditEventRecorder;

  PermissionTransferService(
      PermissionTransferRepository transferRepository,
      PermissionTransferObjectRepository transferObjectRepository,
      AssetGrantRepository grantRepository,
      CapabilityGrantRepository capabilityGrantRepository,
      GroupSubjectDirectory groupDirectory,
      GroupSpaceMembershipDirectory spaceMembershipDirectory,
      GroupStewardshipDirectory stewardshipDirectory,
      List<AssetOwnershipDirectory> assetOwnershipDirectories,
      PermissionHistoryService permissionHistoryService,
      PermissionHistoryClock clock,
      AssetAccessService assetAccessService,
      UserRepository userRepository,
      AuditEventRecorder auditEventRecorder) {
    this.transferRepository = transferRepository;
    this.transferObjectRepository = transferObjectRepository;
    this.grantRepository = grantRepository;
    this.capabilityGrantRepository = capabilityGrantRepository;
    this.groupDirectory = groupDirectory;
    this.spaceMembershipDirectory = spaceMembershipDirectory;
    this.stewardshipDirectory = stewardshipDirectory;
    this.assetOwnershipDirectories = assetOwnershipDirectories;
    this.permissionHistoryService = permissionHistoryService;
    this.clock = clock;
    this.assetAccessService = assetAccessService;
    this.userRepository = userRepository;
    this.auditEventRecorder = auditEventRecorder;
  }

  // -------------------------------------------------------------------------------------------
  // Preview
  // -------------------------------------------------------------------------------------------

  /**
   * What the transfer would move - and the record that somebody looked. Writes {@link
   * AuditEventType#PERMISSION_TRANSFER_PREVIEWED} on every call, so an abandoned preview is on the
   * protocol just like a retrieved Stichtagsauskunft.
   */
  @Transactional
  public PermissionTransferPreview preview(PermissionTransferOrder order, CurrentUser caller) {
    Parties parties = resolve(order, caller);
    PermissionTransferPreview preview = count(parties);
    recordEvent(AuditEventType.PERMISSION_TRANSFER_PREVIEWED, parties, preview.counts(), caller);
    return preview;
  }

  private PermissionTransferPreview count(Parties parties) {
    List<AssetGrant> grants = sourceGrants(parties);
    Set<UUID> grantedAssets = new HashSet<>();
    grants.forEach(grant -> grantedAssets.add(grant.getAssetId()));

    List<UUID> spaceIds =
        parties.scope().contains(PermissionTransferScope.SPACE_MEMBERSHIPS)
                && parties.source().type() == PermissionSubjectType.GROUP
            ? spaceMembershipDirectory.spaceMembershipsOf(List.of(parties.source().id())).stream()
                .map(GroupSpaceMembershipRef::spaceId)
                .toList()
            : List.of();

    int capabilities =
        parties.scope().contains(PermissionTransferScope.CAPABILITIES)
                && parties.source().type() == PermissionSubjectType.GROUP
            ? capabilityGrantRepository.findBySubjectGroupId(parties.source().id()).size()
            : 0;

    int owned =
        parties.scope().contains(PermissionTransferScope.OWNERSHIP)
            ? assetOwnershipDirectories.stream()
                .mapToInt(directory -> directory.assetIdsOwnedBy(parties.source()).size())
                .sum()
            : 0;

    int stewardships =
        parties.scope().contains(PermissionTransferScope.STEWARDSHIP)
            ? stewardshipDirectory
                .stewardedGroupIds(parties.source().id(), parties.organizationId())
                .size()
            : 0;

    return new PermissionTransferPreview(
        parties.source(),
        parties.sourceLabel(),
        parties.target(),
        parties.targetLabel(),
        parties.scope(),
        new PermissionTransferCounts(
            grants.size(), spaceIds.size(), capabilities, owned, stewardships),
        grantedAssets.size(),
        Set.copyOf(spaceIds).size());
  }

  // -------------------------------------------------------------------------------------------
  // Execution
  // -------------------------------------------------------------------------------------------

  /**
   * Carries the transfer out. {@code confirmed} must be true - the confirmation is explicit, and a
   * request without it is a malformed request rather than a silent no-op.
   */
  @Transactional
  public PermissionTransfer transfer(
      PermissionTransferOrder order, boolean confirmed, CurrentUser caller) {
    Parties parties = resolve(order, caller);
    if (!confirmed) {
      throw new ValidationException(
          "Die Übertragung muss ausdrücklich bestätigt werden; rufen Sie zuvor die Vorschau ab.");
    }

    Instant at = clock.nextBoundary();
    PermissionTransfer transfer =
        transferRepository.saveAndFlush(
            new PermissionTransfer(
                parties.organizationId(),
                parties.source(),
                parties.sourceLabel(),
                parties.target(),
                parties.targetLabel(),
                parties.scope(),
                caller.id(),
                at));

    Map<AssetType, Set<UUID>> touched = new LinkedHashMap<>();
    int grants = transferGrants(parties, transfer.getId(), at, caller, touched);
    int spaces = transferSpaceMemberships(parties, transfer.getId(), at, caller, touched);
    int capabilities = transferCapabilities(parties, transfer.getId(), at, caller);
    int owned = transferOwnership(parties, transfer.getId(), at, caller, touched);
    int stewardships = transferStewardships(parties, transfer.getId(), caller);

    transfer.recordCounts(
        new PermissionTransferCounts(grants, spaces, capabilities, owned, stewardships));
    transferRepository.save(transfer);
    touched.forEach(
        (assetType, assetIds) ->
            assetIds.forEach(
                assetId ->
                    transferObjectRepository.save(
                        new PermissionTransferObject(
                            transfer.getId(), parties.organizationId(), assetType, assetId))));

    recordEvent(AuditEventType.PERMISSION_TRANSFER_EXECUTED, parties, transfer.counts(), caller);
    invalidateAfterCompletion(touched);
    return transfer;
  }

  private int transferGrants(
      Parties parties,
      UUID transferId,
      Instant at,
      CurrentUser caller,
      Map<AssetType, Set<UUID>> touched) {
    List<AssetGrant> grants = sourceGrants(parties);
    for (AssetGrant grant : grants) {
      permissionHistoryService.recordGrantTransferredOut(grant, caller.id(), transferId, at);
      Optional<AssetGrant> existing =
          grantRepository.findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
              grant.getAssetType(),
              grant.getAssetId(),
              PermissionSubjectType.GROUP,
              parties.target().id());
      if (existing.isPresent()) {
        AssetGrant target = existing.get();
        AssetRole role = stronger(target.getRole(), grant.getRole());
        Instant expiresAt = laterExpiry(target.getExpiresAt(), grant.getExpiresAt());
        boolean raised =
            role != target.getRole() || !Objects.equals(expiresAt, target.getExpiresAt());
        if (raised) {
          target.updateRole(role, expiresAt, grant.getGrantedByUserId(), at);
          grantRepository.save(target);
          permissionHistoryService.recordGrantTransferredIn(target, caller.id(), transferId, at);
        }
      } else {
        AssetGrant moved =
            AssetGrant.forGroup(
                grant.getAssetType(),
                grant.getAssetId(),
                grant.getOrganizationId(),
                parties.target().id(),
                grant.getRole(),
                grant.getExpiresAt(),
                grant.getGrantedByUserId());
        grantRepository.save(moved);
        permissionHistoryService.recordGrantTransferredIn(moved, caller.id(), transferId, at);
      }
      grantRepository.delete(grant);
      touched
          .computeIfAbsent(grant.getAssetType(), key -> new LinkedHashSet<>())
          .add(grant.getAssetId());
    }
    return grants.size();
  }

  private int transferSpaceMemberships(
      Parties parties,
      UUID transferId,
      Instant at,
      CurrentUser caller,
      Map<AssetType, Set<UUID>> touched) {
    if (!parties.scope().contains(PermissionTransferScope.SPACE_MEMBERSHIPS)) {
      return 0;
    }
    List<UUID> spaceIds =
        spaceMembershipDirectory.transferSpaceMemberships(
            parties.source().id(), parties.target().id(), caller.id(), transferId, at);
    if (!spaceIds.isEmpty()) {
      touched
          .computeIfAbsent(spaceMembershipDirectory.spaceAssetType(), key -> new LinkedHashSet<>())
          .addAll(spaceIds);
    }
    return spaceIds.size();
  }

  private int transferCapabilities(
      Parties parties, UUID transferId, Instant at, CurrentUser caller) {
    if (!parties.scope().contains(PermissionTransferScope.CAPABILITIES)) {
      return 0;
    }
    List<CapabilityGrant> held =
        capabilityGrantRepository.findBySubjectGroupId(parties.source().id());
    for (CapabilityGrant grant : held) {
      permissionHistoryService.recordCapabilityTransferredOut(grant, caller.id(), transferId, at);
      boolean targetHasIt =
          capabilityGrantRepository
              .findByOrganizationIdAndCapabilityAndSubjectTypeAndSubjectGroupId(
                  grant.getOrganizationId(),
                  grant.getCapability(),
                  CapabilitySubjectType.GROUP,
                  parties.target().id())
              .isPresent();
      if (!targetHasIt) {
        CapabilityGrant moved =
            capabilityGrantRepository.save(
                CapabilityGrant.forGroup(
                    grant.getOrganizationId(),
                    grant.getCapability(),
                    parties.target().id(),
                    grant.getGrantedByUserId()));
        permissionHistoryService.recordCapabilityTransferredIn(moved, caller.id(), transferId, at);
      }
      capabilityGrantRepository.delete(grant);
    }
    return held.size();
  }

  private int transferOwnership(
      Parties parties,
      UUID transferId,
      Instant at,
      CurrentUser caller,
      Map<AssetType, Set<UUID>> touched) {
    if (!parties.scope().contains(PermissionTransferScope.OWNERSHIP)) {
      return 0;
    }
    int moved = 0;
    for (AssetOwnershipDirectory directory : assetOwnershipDirectories) {
      for (UUID assetId : directory.assetIdsOwnedBy(parties.source())) {
        directory.transferOwnership(assetId, parties.target(), caller.id(), transferId, at);
        touched.computeIfAbsent(directory.assetType(), key -> new LinkedHashSet<>()).add(assetId);
        moved++;
      }
    }
    return moved;
  }

  private int transferStewardships(Parties parties, UUID transferId, CurrentUser caller) {
    if (!parties.scope().contains(PermissionTransferScope.STEWARDSHIP)) {
      return 0;
    }
    return stewardshipDirectory
        .transferStewardships(
            parties.source().id(),
            parties.target().id(),
            parties.organizationId(),
            caller.id(),
            transferId)
        .size();
  }

  // -------------------------------------------------------------------------------------------
  // The note an object carries afterwards
  // -------------------------------------------------------------------------------------------

  /**
   * The transfer that last touched this object, for its sharing view. Empty for an object no
   * transfer ever touched - the ordinary case, and one extra indexed read per detail view.
   */
  public Optional<PermissionTransferMark> markOf(AssetType assetType, UUID assetId) {
    return transferRepository
        .findTransfersOfAsset(assetType, assetId, PageRequest.of(0, 1))
        .stream()
        .findFirst()
        .map(
            transfer ->
                new PermissionTransferMark(
                    transfer.getId(), transfer.getPerformedAt(), transfer.getSourceLabel()));
  }

  // -------------------------------------------------------------------------------------------
  // Resolution and rules
  // -------------------------------------------------------------------------------------------

  /**
   * The source's grants, or an empty list whenever grants are outside the scope - a person's grants
   * are never listed here, whatever the scope says, and that is enforced in {@link
   * #requireScopeFits} before this is reached.
   */
  private List<AssetGrant> sourceGrants(Parties parties) {
    if (!parties.scope().contains(PermissionTransferScope.ASSET_GRANTS)
        || parties.source().type() != PermissionSubjectType.GROUP) {
      return List.of();
    }
    return grantRepository.findBySubjectGroupIdIn(List.of(parties.source().id()));
  }

  private Parties resolve(PermissionTransferOrder order, CurrentUser caller) {
    if (order.scope() == null || order.scope().isEmpty()) {
      throw new ValidationException("Es muss mindestens eine Wirkungsart übertragen werden");
    }
    if (order.sourceType() == null
        || order.sourceId() == null
        || order.targetType() == null
        || order.targetId() == null) {
      throw new ValidationException("Quelle und Ziel sind erforderlich");
    }
    if (order.sourceType() == order.targetType() && order.sourceId().equals(order.targetId())) {
      throw new ValidationException("Quelle und Ziel sind dasselbe Subjekt");
    }
    Set<PermissionTransferScope> scope = EnumSet.copyOf(order.scope());
    requireScopeFits(order, scope);
    requireAllowed(order, scope, caller);

    UUID organizationId = caller.organizationId();
    String sourceLabel = null;
    String targetLabel = null;
    if (order.sourceType() == PermissionSubjectType.GROUP) {
      sourceLabel = requireGroupInOrganization(order.sourceId(), organizationId).name();
    } else {
      requireUserInOrganization(order.sourceId(), organizationId);
    }
    if (order.targetType() == PermissionSubjectType.GROUP) {
      targetLabel = requireEffectiveTargetGroup(order.targetId(), organizationId).name();
    } else {
      requireUserInOrganization(order.targetId(), organizationId);
    }
    return new Parties(
        new PermissionSubject(order.sourceType(), order.sourceId(), organizationId),
        sourceLabel,
        new PermissionSubject(order.targetType(), order.targetId(), organizationId),
        targetLabel,
        scope,
        organizationId);
  }

  /**
   * Which pairs of subjects admit which parts (ADR-0036, Entscheidung 10): everything between two
   * groups, ownership from a group to a person, ownership and stewardship between two people - and
   * nothing at all from a person to a group, which would make a group responsible for nothing it
   * could act on.
   */
  private void requireScopeFits(PermissionTransferOrder order, Set<PermissionTransferScope> scope) {
    if (order.sourceType() == PermissionSubjectType.USER
        && order.targetType() == PermissionSubjectType.GROUP) {
      throw new ValidationException(
          "Eine Person gibt Eigentum und Verantwortung an eine andere Person ab, nicht an eine"
              + " Gruppe");
    }
    if (order.sourceType() == PermissionSubjectType.USER) {
      Set<PermissionTransferScope> refused = EnumSet.copyOf(scope);
      refused.removeAll(SELF_SERVICE);
      if (!refused.isEmpty()) {
        throw new ValidationException(
            "Von einer Person sind nur Eigentum und Verantwortung übertragbar; Berechtigungen,"
                + " Space-Mitgliedschaften und Anlegerechte einer Person werden hier weder"
                + " übertragen noch aufgezählt.");
      }
    }
    if (order.sourceType() == PermissionSubjectType.GROUP
        && scope.contains(PermissionTransferScope.STEWARDSHIP)) {
      throw new ValidationException("Eine Gruppe ist nie verantwortlich für eine Gruppe");
    }
    if (order.targetType() == PermissionSubjectType.USER
        && scope.stream().anyMatch(GROUP_TO_GROUP_ONLY::contains)) {
      throw new ValidationException(
          "Berechtigungen, Space-Mitgliedschaften und Anlegerechte werden nur von Gruppe zu Gruppe"
              + " übertragen");
    }
  }

  /**
   * A system administrator may transfer within their organization; anybody else only their own load
   * - the ownership and the responsibility they carry themselves ("Abgabe aus Meine Gruppen"). The
   * mass operation stays an administrative act otherwise: a MANAGER changes the grants on their own
   * asset one at a time.
   */
  private void requireAllowed(
      PermissionTransferOrder order, Set<PermissionTransferScope> scope, CurrentUser caller) {
    if (caller.isSystemAdmin()) {
      return;
    }
    boolean ownLoad =
        order.sourceType() == PermissionSubjectType.USER && order.sourceId().equals(caller.id());
    if (!ownLoad || !SELF_SERVICE.containsAll(scope)) {
      throw new AccessDeniedException(
          "Nur die Systemverwaltung überträgt Rechte; abgeben können Sie das Eigentum und die"
              + " Verantwortung, die Sie selbst tragen.");
    }
  }

  private GroupSubject requireGroupInOrganization(UUID groupId, UUID organizationId) {
    return groupDirectory
        .find(groupId)
        .filter(group -> group.organizationId().equals(organizationId))
        .orElseThrow(() -> new NotFoundException("Gruppe nicht gefunden"));
  }

  /**
   * The target must be an effective group (ADR-0036, Begriffe) - <b>it may be empty</b>: in token
   * mode the new provider's group comes into being with the first sign-in, and a transfer that
   * waited for that would be the one thing the provider replacement cannot do. The source is held
   * to no such rule: a dissolved group is the normal case here.
   */
  private GroupSubject requireEffectiveTargetGroup(UUID groupId, UUID organizationId) {
    GroupSubject group = requireGroupInOrganization(groupId, organizationId);
    if (group.dissolved()) {
      throw new ValidationException(
          "Die Zielgruppe ist aufgelöst und kann keine Rechte mehr übernehmen");
    }
    if (group.providerDisabled()) {
      throw new ValidationException(
          "Der Identitätsanbieter der Zielgruppe ist deaktiviert. Sie kann keine Rechte übernehmen,"
              + " solange er es bleibt.");
    }
    if (group.unmaintained()) {
      throw new ValidationException(
          "Die Zielgruppe stammt aus dem Gruppen-Claim eines Anbieters, der inzwischen über den"
              + " Verzeichnisabgleich gepflegt wird. Ihre Mitgliedschaft ist eingefroren; übertragen"
              + " Sie auf die entsprechende Organisationseinheit.");
    }
    return group;
  }

  private User requireUserInOrganization(UUID userId, UUID organizationId) {
    return userRepository
        .findById(userId)
        .filter(user -> user.getOrganizationId().equals(organizationId))
        .orElseThrow(() -> new NotFoundException("Benutzer nicht gefunden"));
  }

  private static AssetRole stronger(AssetRole one, AssetRole other) {
    return one.ordinal() >= other.ordinal() ? one : other;
  }

  /** {@code null} means "never expires" and therefore beats every date. */
  private static Instant laterExpiry(Instant one, Instant other) {
    if (one == null || other == null) {
      return null;
    }
    return one.isAfter(other) ? one : other;
  }

  /**
   * Quelle, Ziel, Umfang und Zahl der Zeilen - what ADR-0036, Entscheidung 10 asks the audit entry
   * to carry. The object is the source (a group, or the account of a succession), so a reader finds
   * the operation where they look for it; the target rides in the payload, since an entry names one
   * object.
   */
  private void recordEvent(
      AuditEventType type, Parties parties, PermissionTransferCounts counts, CurrentUser caller) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("targetType", parties.target().type().name());
    payload.put("targetId", parties.target().id().toString());
    payload.put("scope", parties.scope().stream().map(Enum::name).toList());
    payload.put("assetGrants", counts.assetGrants());
    payload.put("spaceMemberships", counts.spaceMemberships());
    payload.put("capabilities", counts.capabilities());
    payload.put("ownedAssets", counts.ownedAssets());
    payload.put("stewardships", counts.stewardships());
    payload.put("rows", counts.total());

    boolean groupSource = parties.source().type() == PermissionSubjectType.GROUP;
    AuditEvent.Builder event =
        AuditEvent.builder()
            .organizationId(parties.organizationId())
            .actor(caller.id())
            .type(type)
            .object(
                groupSource ? AuditObjectType.GROUP : AuditObjectType.USER_ACCOUNT,
                parties.source().id(),
                parties.sourceLabel())
            .after(payload)
            .outcome(AuditOutcome.SUCCESS);
    if (groupSource) {
      auditEventRecorder.recordUserAction(event.build());
      return;
    }
    auditEventRecorder.recordUserActionOnSubject(
        event.subject(AuditSubjectKind.USER, parties.source().id()).build());
  }

  /**
   * Evicts the per-asset grant cache of every touched asset once the transaction has finished - the
   * same {@code afterCompletion} choice {@code AssetGrantService} makes, so a rollback evicts too.
   */
  private void invalidateAfterCompletion(Map<AssetType, Set<UUID>> touched) {
    List<Runnable> evictions = new ArrayList<>();
    touched.forEach(
        (assetType, assetIds) ->
            assetIds.forEach(
                assetId ->
                    evictions.add(() -> assetAccessService.invalidateAsset(assetType, assetId))));
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      evictions.forEach(Runnable::run);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            evictions.forEach(Runnable::run);
          }
        });
  }

  /** The validated, resolved form of a {@link PermissionTransferOrder}. */
  private record Parties(
      PermissionSubject source,
      String sourceLabel,
      PermissionSubject target,
      String targetLabel,
      Set<PermissionTransferScope> scope,
      UUID organizationId) {}
}
