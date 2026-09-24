package io.opaa.permission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.time.Duration;
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

  /** The stable {@code code} of the {@code 409} a missing or outdated preview produces. */
  public static final String PREVIEW_REQUIRED = "TRANSFER_PREVIEW_REQUIRED";

  /** How long a shown preview may be acted upon. */
  private static final Duration PREVIEW_VALIDITY = Duration.ofMinutes(30);

  /** The most rows one transfer moves - see {@link #requireWithinTheWorkLimit}. */
  static final int MAX_ROWS_PER_TRANSFER = 500;

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
  private final SuccessionCaseCloser successionCases;

  /**
   * The previews shown but not yet acted upon, keyed by the id the caller gets back. Bounded by
   * {@link #PREVIEW_VALIDITY} and by a size cap, so a caller who previews and walks away leaks
   * nothing.
   */
  private final Cache<UUID, ShownPreview> shownPreviews =
      Caffeine.newBuilder().expireAfterWrite(PREVIEW_VALIDITY).maximumSize(1_000).build();

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
      AuditEventRecorder auditEventRecorder,
      SuccessionCaseCloser successionCases) {
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
    this.successionCases = successionCases;
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
    requireWithinTheWorkLimit(parties);
    PermissionTransferSnapshot snapshot = snapshot(parties);
    UUID previewId = UUID.randomUUID();
    PermissionTransferPreview preview = describe(parties, snapshot, previewId);
    shownPreviews.put(
        previewId,
        new ShownPreview(
            caller.id(), signatureOf(parties), PermissionTransferFingerprint.of(snapshot)));
    recordEvent(AuditEventType.PERMISSION_TRANSFER_PREVIEWED, parties, preview.counts(), caller);
    return preview;
  }

  /**
   * Everything the operation would move, read once. Called only after {@link
   * #requireWithinTheWorkLimit} has decided on plain counts that the load is bounded.
   */
  private PermissionTransferSnapshot snapshot(Parties parties) {
    boolean groupSource = parties.source().type() == PermissionSubjectType.GROUP;
    List<AssetGrant> grants =
        parties.scope().contains(PermissionTransferScope.ASSET_GRANTS) && groupSource
            ? grantRepository.findBySubjectGroupIdIn(List.of(parties.source().id()))
            : List.of();
    List<GroupSpaceMembershipRef> spaceMemberships =
        parties.scope().contains(PermissionTransferScope.SPACE_MEMBERSHIPS) && groupSource
            ? spaceMembershipDirectory.spaceMembershipsOf(List.of(parties.source().id()))
            : List.of();
    List<CapabilityGrant> capabilities =
        parties.scope().contains(PermissionTransferScope.CAPABILITIES) && groupSource
            ? capabilityGrantRepository.findBySubjectGroupId(parties.source().id())
            : List.of();
    Map<AssetType, List<UUID>> owned = new LinkedHashMap<>();
    if (parties.scope().contains(PermissionTransferScope.OWNERSHIP)) {
      for (AssetOwnershipDirectory directory : assetOwnershipDirectories) {
        directory
            .assetIdsOwnedBy(parties.source())
            .forEach(
                (assetType, assetIds) -> {
                  if (!assetIds.isEmpty()) {
                    owned.computeIfAbsent(assetType, key -> new ArrayList<>()).addAll(assetIds);
                  }
                });
      }
    }
    List<UUID> stewarded =
        parties.scope().contains(PermissionTransferScope.STEWARDSHIP)
            ? stewardshipDirectory.stewardedGroupIds(
                parties.source().id(), parties.organizationId())
            : List.of();
    return new PermissionTransferSnapshot(grants, spaceMemberships, capabilities, owned, stewarded);
  }

  /**
   * The figures a preview shows. Expired grants are left out of them: they are ended by the
   * transfer but granted to nobody again, and a count that included them would promise more reach
   * than the operation delivers.
   */
  private PermissionTransferPreview describe(
      Parties parties, PermissionTransferSnapshot snapshot, UUID previewId) {
    Instant now = Instant.now();
    List<AssetGrant> effective =
        snapshot.grants().stream().filter(grant -> !grant.isExpired(now)).toList();
    Set<UUID> grantedAssets = new HashSet<>();
    effective.forEach(grant -> grantedAssets.add(grant.getAssetId()));
    Set<UUID> spaceIds = new HashSet<>();
    snapshot.spaceMemberships().forEach(membership -> spaceIds.add(membership.spaceId()));
    int owned = snapshot.ownedAssets().values().stream().mapToInt(List::size).sum();

    return new PermissionTransferPreview(
        previewId,
        parties.source(),
        parties.sourceLabel(),
        parties.target(),
        parties.targetLabel(),
        parties.scope(),
        new PermissionTransferCounts(
            effective.size(),
            snapshot.spaceMemberships().size(),
            snapshot.capabilities().size(),
            owned,
            snapshot.stewardedGroups().size()),
        grantedAssets.size(),
        spaceIds.size());
  }

  // -------------------------------------------------------------------------------------------
  // Execution
  // -------------------------------------------------------------------------------------------

  /**
   * Carries the transfer out. <b>Only against a preview this caller has just been shown</b>
   * (ADR-0036, Entscheidung 10 - "Die Vorschau ist Pflicht"): {@code previewId} names it, and what
   * it showed must still be what stands. {@code confirmed} must be true on top of that - the
   * confirmation is an explicit act, not a default.
   */
  @Transactional
  public PermissionTransfer transfer(
      PermissionTransferOrder order, boolean confirmed, UUID previewId, CurrentUser caller) {
    Parties parties = resolve(order, caller);
    if (!confirmed) {
      throw new ValidationException(
          "Die Übertragung muss ausdrücklich bestätigt werden; rufen Sie zuvor die Vorschau ab.");
    }
    requireWithinTheWorkLimit(parties);
    PermissionTransferSnapshot snapshot = snapshot(parties);
    requireUnchangedPreview(previewId, parties, snapshot, caller);

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
    int grants = transferGrants(parties, snapshot, transfer.getId(), at, caller, touched);
    int spaces = transferSpaceMemberships(parties, transfer.getId(), at, caller, touched);
    int capabilities = transferCapabilities(parties, snapshot, transfer.getId(), at, caller);
    int owned = transferOwnership(parties, snapshot, transfer.getId(), at, caller, touched);
    List<UUID> stewarded = transferStewardships(parties, transfer.getId(), caller);
    int stewardships = stewarded.size();

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

    // #1819: the Übernahme is this operation - so it ends the succession record of every object it
    // touched and names the person who did it, instead of leaving the run to close it anonymously.
    touched.forEach(
        (assetType, assetIds) ->
            assetIds.forEach(
                assetId -> successionCases.closeForAsset(assetType, assetId, caller.id())));
    stewarded.forEach(groupId -> successionCases.closeForGroup(groupId, caller.id()));

    shownPreviews.invalidate(previewId);
    recordEvent(AuditEventType.PERMISSION_TRANSFER_EXECUTED, parties, transfer.counts(), caller);
    invalidateAfterCompletion(touched);
    return transfer;
  }

  /**
   * The preview this execution stands on: it must exist, belong to this caller, name the same
   * subjects and the same scope, and still describe the current state. Anything else is answered
   * with a conflict and a fresh preview - the treatment a directory-sync plan gets when the
   * snapshot moved under it (ADR-0036, Entscheidung 3).
   *
   * <p>Held in memory rather than in a table: a preview is one step of one session, valid for
   * {@link #PREVIEW_VALIDITY}. ADR-0021 (one process) carries that; after a restart the caller
   * previews again, which costs one request and one more protocol entry.
   */
  private void requireUnchangedPreview(
      UUID previewId, Parties parties, PermissionTransferSnapshot snapshot, CurrentUser caller) {
    ShownPreview shown = previewId == null ? null : shownPreviews.getIfPresent(previewId);
    if (shown == null
        || !shown.callerUserId().equals(caller.id())
        || !shown.signature().equals(signatureOf(parties))) {
      throw new ConflictException(
          "Zu dieser Übertragung liegt keine gültige Vorschau vor. Rufen Sie die Vorschau erneut"
              + " ab und bestätigen Sie sie.",
          PREVIEW_REQUIRED);
    }
    if (!shown.fingerprint().equals(PermissionTransferFingerprint.of(snapshot))) {
      shownPreviews.invalidate(previewId);
      throw new ConflictException(
          "Der Stand hat sich seit der Vorschau geändert. Die Übertragung wurde nicht ausgeführt;"
              + " rufen Sie die Vorschau erneut ab.",
          PREVIEW_REQUIRED);
    }
  }

  /**
   * The one hard limit of the operation. Every moved row costs a history read, a flushed close, an
   * insert and a delete, all in one transaction holding rows in up to four history tables - and the
   * occasions this operation exists for (a provider replacement, a dissolved Referat) are exactly
   * the ones that can carry thousands. Above the limit the transfer is refused with the figure and
   * the way out: a smaller scope, one part at a time.
   */
  private void requireWithinTheWorkLimit(Parties parties) {
    long workload = workloadOf(parties);
    if (workload > MAX_ROWS_PER_TRANSFER) {
      throw new ValidationException(
          "Diese Übertragung bewegt "
              + workload
              + " Zeilen und liegt damit über der Grenze von "
              + MAX_ROWS_PER_TRANSFER
              + " je Vorgang. Übertragen Sie in mehreren Schritten - etwa erst die Berechtigungen,"
              + " dann das Eigentum.");
    }
  }

  /**
   * What the transfer would touch, counted rather than loaded - expired grants included: they are
   * removed from the source like any other row (an expired grant still blocks the RESTRICT key of a
   * group deletion) and are only not granted to the target again. Counting first is what keeps the
   * load itself bounded: a group with a hundred thousand grants is refused before a single row
   * reaches the application.
   */
  private long workloadOf(Parties parties) {
    boolean groupSource = parties.source().type() == PermissionSubjectType.GROUP;
    long workload = 0;
    if (parties.scope().contains(PermissionTransferScope.ASSET_GRANTS) && groupSource) {
      workload += grantRepository.countBySubjectGroupId(parties.source().id());
    }
    if (parties.scope().contains(PermissionTransferScope.SPACE_MEMBERSHIPS) && groupSource) {
      workload += spaceMembershipDirectory.countSpaceMembershipsOf(parties.source().id());
    }
    if (parties.scope().contains(PermissionTransferScope.CAPABILITIES) && groupSource) {
      workload += capabilityGrantRepository.countBySubjectGroupId(parties.source().id());
    }
    if (parties.scope().contains(PermissionTransferScope.OWNERSHIP)) {
      for (AssetOwnershipDirectory directory : assetOwnershipDirectories) {
        workload += directory.countAssetsOwnedBy(parties.source());
      }
    }
    if (parties.scope().contains(PermissionTransferScope.STEWARDSHIP)) {
      workload +=
          stewardshipDirectory.countStewardedGroups(
              parties.source().id(), parties.organizationId());
    }
    return workload;
  }

  private PreviewSignature signatureOf(Parties parties) {
    return new PreviewSignature(
        parties.source().type(),
        parties.source().id(),
        parties.target().type(),
        parties.target().id(),
        EnumSet.copyOf(parties.scope()));
  }

  private int transferGrants(
      Parties parties,
      PermissionTransferSnapshot snapshot,
      UUID transferId,
      Instant at,
      CurrentUser caller,
      Map<AssetType, Set<UUID>> touched) {
    List<AssetGrant> grants = snapshot.grants();
    int transferred = 0;
    for (AssetGrant grant : grants) {
      permissionHistoryService.recordGrantTransferredOut(grant, caller.id(), transferId, at);
      if (grant.isExpired(at)) {
        // Ended, not handed on: an expired grant confers nothing, so the target would receive a
        // dead row. The source's row still has to go - it blocks the RESTRICT key of a deletion.
        grantRepository.delete(grant);
        touched
            .computeIfAbsent(grant.getAssetType(), key -> new LinkedHashSet<>())
            .add(grant.getAssetId());
        continue;
      }
      transferred++;
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
                grant.getGrantedByUserId(),
                // Die Uebertragung nimmt die Zahl der Erteilung mit: Sie gehoert zu diesem Recht,
                // nicht zur Gruppe, die es bisher trug (#1820, ADR-0036 Entscheidung 9).
                grant.getMemberCountAtGrant());
        grantRepository.save(moved);
        permissionHistoryService.recordGrantTransferredIn(moved, caller.id(), transferId, at);
      }
      grantRepository.delete(grant);
      touched
          .computeIfAbsent(grant.getAssetType(), key -> new LinkedHashSet<>())
          .add(grant.getAssetId());
    }
    return transferred;
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
      Parties parties,
      PermissionTransferSnapshot snapshot,
      UUID transferId,
      Instant at,
      CurrentUser caller) {
    List<CapabilityGrant> held = snapshot.capabilities();
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
      PermissionTransferSnapshot snapshot,
      UUID transferId,
      Instant at,
      CurrentUser caller,
      Map<AssetType, Set<UUID>> touched) {
    int moved = 0;
    for (Map.Entry<AssetType, List<UUID>> owned : snapshot.ownedAssets().entrySet()) {
      AssetType assetType = owned.getKey();
      AssetOwnershipDirectory directory =
          assetOwnershipDirectories.stream()
              .filter(candidate -> candidate.answersFor(assetType))
              .findFirst()
              .orElseThrow(
                  () -> new IllegalStateException("no ownership directory for " + assetType));
      for (UUID assetId : owned.getValue()) {
        directory.transferOwnership(
            assetType, assetId, parties.target(), caller.id(), transferId, at);
        touched.computeIfAbsent(assetType, key -> new LinkedHashSet<>()).add(assetId);
        moved++;
      }
    }
    return moved;
  }

  /**
   * The groups whose responsibility moved - returned rather than counted, so the caller can end
   * their succession records by name.
   */
  private List<UUID> transferStewardships(Parties parties, UUID transferId, CurrentUser caller) {
    if (!parties.scope().contains(PermissionTransferScope.STEWARDSHIP)) {
      return List.of();
    }
    return stewardshipDirectory.transferStewardships(
        parties.source().id(),
        parties.target().id(),
        parties.organizationId(),
        caller.id(),
        transferId);
  }

  // -------------------------------------------------------------------------------------------
  // The note an object carries afterwards
  // -------------------------------------------------------------------------------------------

  /**
   * The transfer that last touched this object, for its sharing view - resolved for {@code caller}.
   * Empty for an object no transfer ever touched: the ordinary case, and one extra indexed read per
   * detail view.
   *
   * <p><b>Whether the source group is named is the caller's question, not the record's</b>
   * (ADR-0036, Entscheidung 9). The snapshot in {@code permission_transfers.source_label} is
   * complete; who gets to read it is decided here: a protected group is never named, a group the
   * caller may not see is not named either, and a source group that no longer exists is named to
   * the system administration alone - nobody can ask a deleted group about its visibility any more.
   */
  public Optional<PermissionTransferMark> markOf(
      AssetType assetType, UUID assetId, CurrentUser caller) {
    return transferRepository
        .findTransfersOfAsset(assetType, assetId, caller.organizationId(), PageRequest.of(0, 1))
        .stream()
        .findFirst()
        .map(transfer -> toMark(transfer, caller));
  }

  private PermissionTransferMark toMark(PermissionTransfer transfer, CurrentUser caller) {
    if (transfer.source().type() != PermissionSubjectType.GROUP) {
      return new PermissionTransferMark(transfer.getId(), transfer.getPerformedAt(), null, false);
    }
    UUID sourceGroupId = transfer.source().id();
    GroupSubject group = groupDirectory.find(sourceGroupId).orElse(null);
    if (group == null) {
      return new PermissionTransferMark(
          transfer.getId(),
          transfer.getPerformedAt(),
          caller.isSystemAdmin() ? transfer.getSourceLabel() : null,
          false);
    }
    if (group.protectedGroup()) {
      return new PermissionTransferMark(transfer.getId(), transfer.getPerformedAt(), null, true);
    }
    boolean visible =
        groupDirectory.isSelectableBy(sourceGroupId, caller.id(), caller.isSystemAdmin());
    return new PermissionTransferMark(
        transfer.getId(),
        transfer.getPerformedAt(),
        visible ? transfer.getSourceLabel() : null,
        false);
  }

  // -------------------------------------------------------------------------------------------
  // Resolution and rules
  // -------------------------------------------------------------------------------------------

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

  /** What a preview was shown for - the execution must name exactly the same. */
  private record PreviewSignature(
      PermissionSubjectType sourceType,
      UUID sourceId,
      PermissionSubjectType targetType,
      UUID targetId,
      Set<PermissionTransferScope> scope) {}

  /** One preview shown to one caller, with the print of the rows it showed. */
  private record ShownPreview(UUID callerUserId, PreviewSignature signature, String fingerprint) {}

  /** The validated, resolved form of a {@link PermissionTransferOrder}. */
  private record Parties(
      PermissionSubject source,
      String sourceLabel,
      PermissionSubject target,
      String targetLabel,
      Set<PermissionTransferScope> scope,
      UUID organizationId) {}
}
