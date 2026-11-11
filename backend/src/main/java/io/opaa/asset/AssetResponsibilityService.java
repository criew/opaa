package io.opaa.asset;

import io.opaa.api.types.AssetLockCause;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.permission.GroupCapabilityService;
import io.opaa.permission.GroupSubject;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.SuccessionFinding;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The responsible unit and the lock of agents and document templates, the shell's one mechanism for
 * both types. A unit is required on creation and validated here; a change, a lock and an unlock
 * each write one audit entry, using an object writes none. The lock is read from the table on every
 * call and never cached, so it holds on every instance from the next call on (ADR-0053).
 *
 * <p>The responsibility counts as missing while no unit is named, the named group is unknown,
 * dissolved, switched off or deactivated, or the object's owner is in open succession. {@link
 * ResponsibilityLockRun} starts the period and locks once it is over; nothing is deleted.
 */
@Service
@Transactional(readOnly = true)
public class AssetResponsibilityService {

  /** The field every validation message names. */
  public static final String FIELD = "responsible";

  static final int MAX_FUNCTION_LENGTH = 200;
  static final int MAX_REASON_LENGTH = 500;

  private static final String ADMIN_ONLY = "Nur die Systemverwaltung darf sperren und entsperren.";

  private final AssetResponsibilityRepository repository;
  private final GroupSubjectDirectory groupDirectory;
  private final GroupCapabilityService groupCapability;
  private final AssetSuccessionSource successionSource;
  private final ResponsibilityLockPeriod lockPeriod;
  private final AssetTypes assetTypes;
  private final AuditEventRecorder auditEventRecorder;
  private final Clock clock;

  AssetResponsibilityService(
      AssetResponsibilityRepository repository,
      GroupSubjectDirectory groupDirectory,
      GroupCapabilityService groupCapability,
      AssetSuccessionSource successionSource,
      ResponsibilityLockPeriod lockPeriod,
      AssetTypes assetTypes,
      AuditEventRecorder auditEventRecorder,
      Clock clock) {
    this.repository = repository;
    this.groupDirectory = groupDirectory;
    this.groupCapability = groupCapability;
    this.successionSource = successionSource;
    this.lockPeriod = lockPeriod;
    this.assetTypes = assetTypes;
    this.auditEventRecorder = auditEventRecorder;
    this.clock = clock;
  }

  /**
   * The unit to store, or {@code null} with a field error added. Exactly one of group and function;
   * the group must be one of the caller's organization the caller may select and that still acts.
   */
  public ResponsibleUnit validate(
      ResponsibleUnit submitted, CurrentUser caller, List<FieldError> errors) {
    String function =
        submitted == null || submitted.function() == null ? null : submitted.function().strip();
    if (function != null && function.isEmpty()) {
      function = null;
    }
    UUID groupId = submitted == null ? null : submitted.groupId();
    if (groupId == null && function == null) {
      errors.add(
          new FieldError(
              FIELD,
              "REQUIRED",
              "Geben Sie die verantwortliche Stelle an: eine Gruppe, Organisationseinheit oder"
                  + " Funktion."));
      return null;
    }
    if (groupId != null && function != null) {
      errors.add(
          new FieldError(
              FIELD,
              "AMBIGUOUS",
              "Nennen Sie entweder eine Gruppe oder eine Funktion, nicht beides."));
      return null;
    }
    if (function != null) {
      if (function.length() > MAX_FUNCTION_LENGTH) {
        errors.add(
            new FieldError(
                FIELD,
                "TOO_LONG",
                "Die verantwortliche Stelle hat höchstens " + MAX_FUNCTION_LENGTH + " Zeichen."));
        return null;
      }
      return ResponsibleUnit.ofFunction(function);
    }
    GroupSubject group = groupDirectory.find(groupId).orElse(null);
    if (group == null
        || !group.organizationId().equals(caller.organizationId())
        || !groupDirectory.isSelectableBy(groupId, caller.id(), caller.isSystemAdmin())
        || !groupCapability.isEffective(group)) {
      errors.add(
          new FieldError(
              FIELD,
              "UNKNOWN",
              "Diese Gruppe gibt es nicht, oder sie kann keine Verantwortung übernehmen."));
      return null;
    }
    return ResponsibleUnit.ofGroup(groupId);
  }

  /** Throws the field error of {@link #validate} when there is one; else the unit to store. */
  public ResponsibleUnit requireValid(ResponsibleUnit submitted, CurrentUser caller) {
    List<FieldError> errors = new ArrayList<>();
    ResponsibleUnit unit = validate(submitted, caller, errors);
    if (!errors.isEmpty()) {
      throw new FieldValidationException("Bitte prüfen Sie die markierten Angaben.", errors);
    }
    return unit;
  }

  /** Records the unit of a just-created object; its creation entry is the audit of this step. */
  @Transactional
  public void establish(Asset asset, ResponsibleUnit unit) {
    repository.save(
        new AssetResponsibility(
            asset.getId(), asset.getOrganizationId(), Objects.requireNonNull(unit)));
  }

  /**
   * Replaces the unit of an object the caller manages (the type's service has checked that); an
   * unchanged unit writes nothing. Returns whether it changed.
   */
  @Transactional
  public boolean change(Asset asset, ResponsibleUnit unit, CurrentUser caller) {
    Objects.requireNonNull(unit);
    AssetResponsibility row = rowOf(asset);
    ResponsibleUnit before = row.unit();
    if (unit.equals(before)) {
      return false;
    }
    row.assign(unit);
    repository.save(row);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(asset.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.ASSET_RESPONSIBILITY_CHANGED)
            .object(auditObjectType(asset), asset.getId(), asset.auditName())
            .before(asset.auditPayload(unitPayload(before)))
            .after(asset.auditPayload(unitPayload(unit)))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return true;
  }

  /**
   * Locks the object for the system administration with a required reason; a locked object is
   * locked again with the new reason. The caller has loaded the object in its organization.
   */
  @Transactional
  public AssetResponsibilityView lock(Asset asset, String reason, CurrentUser caller) {
    caller.requireSystemAdmin(ADMIN_ONLY);
    String normalized = reason == null ? "" : reason.strip();
    if (normalized.isEmpty()) {
      throw FieldValidationException.of("reason", "REQUIRED", "Geben Sie an, warum gesperrt wird.");
    }
    if (normalized.length() > MAX_REASON_LENGTH) {
      throw FieldValidationException.of(
          "reason", "TOO_LONG", "Der Grund hat höchstens " + MAX_REASON_LENGTH + " Zeichen.");
    }
    AssetResponsibility row = rowOf(asset);
    row.lock(AssetLockCause.MANUAL, normalized, Instant.now(clock));
    repository.save(row);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(asset.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.ASSET_LOCKED)
            .object(auditObjectType(asset), asset.getId(), asset.auditName())
            .after(asset.auditPayload(Map.of("cause", AssetLockCause.MANUAL.name())))
            .reason(normalized)
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return view(asset, caller, null);
  }

  /** Lifts the lock, whatever its cause; an object without lock answers unchanged. */
  @Transactional
  public AssetResponsibilityView unlock(Asset asset, CurrentUser caller) {
    caller.requireSystemAdmin(ADMIN_ONLY);
    AssetResponsibility row = rowOf(asset);
    if (row.isLocked()) {
      AssetLockCause cause = row.getLockCause();
      row.unlock();
      repository.save(row);
      auditEventRecorder.recordUserAction(
          AuditEvent.builder()
              .organizationId(asset.getOrganizationId())
              .actor(caller.id())
              .type(AuditEventType.ASSET_UNLOCKED)
              .object(auditObjectType(asset), asset.getId(), asset.auditName())
              .before(asset.auditPayload(Map.of("cause", cause.name())))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    return view(asset, caller, null);
  }

  /** Whether the object is locked right now; read from the table on every call. */
  public boolean isLocked(UUID assetId) {
    return !repository.findLockedIdsAmong(Set.of(assetId)).isEmpty();
  }

  /** Those of the given objects that are locked right now, in one query. */
  public Set<UUID> lockedAmong(Collection<UUID> assetIds) {
    if (assetIds.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(repository.findLockedIdsAmong(assetIds));
  }

  /** {@link #viewsOf} for one object the caller holds {@code role} on. */
  public AssetResponsibilityView view(Asset asset, CurrentUser caller, AssetRole role) {
    Map<UUID, AssetRole> roles = new HashMap<>();
    roles.put(asset.getId(), role);
    return viewsOf(List.of(asset), caller, roles).get(asset.getId());
  }

  /**
   * The responsibility of each object, with one lookup of rows, groups and succession for all. The
   * reason of a lock goes only to the system administration and to whoever holds at least {@code
   * MANAGER} by {@code roles}; a reader learns only since when the object is locked.
   */
  public Map<UUID, AssetResponsibilityView> viewsOf(
      Collection<? extends Asset> assets, CurrentUser caller, Map<UUID, AssetRole> roles) {
    if (assets.isEmpty()) {
      return Map.of();
    }
    Map<UUID, AssetResponsibility> rows =
        repository.findAllById(assets.stream().map(Asset::getId).toList()).stream()
            .collect(Collectors.toMap(AssetResponsibility::getAssetId, Function.identity()));
    Set<UUID> groupIds =
        rows.values().stream()
            .map(AssetResponsibility::getResponsibleGroupId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<UUID, String> groupNames = groupDirectory.displayNamesById(groupIds);
    Map<UUID, Boolean> groupActs = groupsAct(groupIds);
    Map<UUID, SuccessionFinding> succession = successionSource.findingsAmong(assets, false);
    Duration period = Duration.ofDays(lockPeriod.days());
    Map<UUID, AssetResponsibilityView> views = new LinkedHashMap<>();
    for (Asset asset : assets) {
      AssetResponsibility row = rows.get(asset.getId());
      if (row == null) {
        views.put(
            asset.getId(), new AssetResponsibilityView(null, null, null, true, null, null, null));
        continue;
      }
      boolean missing = isMissing(row, groupActs, succession.containsKey(asset.getId()));
      Instant missingSince = missing ? row.getMissingSince() : null;
      Instant lockDueAt =
          missingSince != null && !row.isLocked() ? missingSince.plus(period) : null;
      AssetResponsibilityView.Lock lock =
          row.isLocked()
              ? new AssetResponsibilityView.Lock(
                  row.getLockCause(),
                  seesReason(caller, roles.get(asset.getId())) ? row.getLockReason() : null,
                  row.getLockedAt())
              : null;
      UUID groupId = row.getResponsibleGroupId();
      views.put(
          asset.getId(),
          new AssetResponsibilityView(
              groupId,
              groupId == null ? null : groupNames.get(groupId),
              row.getResponsibleFunction(),
              missing,
              missingSince,
              lockDueAt,
              lock));
    }
    return views;
  }

  private static boolean seesReason(CurrentUser caller, AssetRole role) {
    return caller.isSystemAdmin() || (role != null && role.atLeast(AssetRole.MANAGER));
  }

  /** Whether each group still acts as a responsible unit: known, and effective. */
  Map<UUID, Boolean> groupsAct(Collection<UUID> groupIds) {
    Map<UUID, Boolean> acts = new HashMap<>();
    for (UUID groupId : groupIds) {
      acts.put(
          groupId, groupDirectory.find(groupId).map(groupCapability::isEffective).orElse(false));
    }
    return acts;
  }

  static boolean isMissing(
      AssetResponsibility row, Map<UUID, Boolean> groupActs, boolean ownerInSuccession) {
    if (ownerInSuccession) {
      return true;
    }
    if (row.getResponsibleFunction() != null) {
      return false;
    }
    UUID groupId = row.getResponsibleGroupId();
    return groupId == null || !groupActs.getOrDefault(groupId, false);
  }

  private AssetResponsibility rowOf(Asset asset) {
    return repository
        .findByIdForUpdate(asset.getId())
        .orElseGet(() -> new AssetResponsibility(asset.getId(), asset.getOrganizationId(), null));
  }

  private AuditObjectType auditObjectType(Asset asset) {
    return assetTypes.require(asset.getAssetType()).auditObjectType();
  }

  private static Map<String, Object> unitPayload(ResponsibleUnit unit) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (unit != null && unit.groupId() != null) {
      payload.put("responsibleGroupId", unit.groupId().toString());
    }
    if (unit != null && unit.function() != null) {
      payload.put("responsibleFunction", unit.function());
    }
    return payload;
  }
}
