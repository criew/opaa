package io.opaa.asset;

import io.opaa.api.types.AssetLockCause;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.DatabaseClock;
import io.opaa.permission.SuccessionFinding;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One pass of the period without a responsible unit: for every agent and document template it
 * starts the period when the unit went missing, ends it when the unit is back, and locks the object
 * once the period is over - never deletes it. Each object is decided in its own short transaction;
 * the lock and its audit entry stand behind a conditional update, so a second instance or a
 * repeated pass locks and audits nothing twice (ADR-0053, Entscheidung 7).
 */
@Service
public class ResponsibilityLockRun {

  /** The process the audit entry of an automatic lock names as actor. */
  public static final String ACTOR = "responsibility-lock";

  private static final Logger log = LoggerFactory.getLogger(ResponsibilityLockRun.class);
  private static final int PAGE_SIZE = 500;
  private static final UUID FIRST = new UUID(Long.MIN_VALUE, Long.MIN_VALUE);

  private final AssetResponsibilityRepository repository;
  private final AssetRepository assetRepository;
  private final AssetResponsibilityService service;
  private final AssetSuccessionSource successionSource;
  private final ResponsibilityLockPeriod lockPeriod;
  private final AssetTypes assetTypes;
  private final AuditEventRecorder auditEventRecorder;
  private final DatabaseClock databaseClock;
  private final TransactionTemplate transaction;

  ResponsibilityLockRun(
      AssetResponsibilityRepository repository,
      AssetRepository assetRepository,
      AssetResponsibilityService service,
      AssetSuccessionSource successionSource,
      ResponsibilityLockPeriod lockPeriod,
      AssetTypes assetTypes,
      AuditEventRecorder auditEventRecorder,
      DatabaseClock databaseClock,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.assetRepository = assetRepository;
    this.service = service;
    this.successionSource = successionSource;
    this.lockPeriod = lockPeriod;
    this.assetTypes = assetTypes;
    this.auditEventRecorder = auditEventRecorder;
    this.databaseClock = databaseClock;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /** Runs over every row; returns how many objects it locked. */
  public int runOnce() {
    int days = lockPeriod.days();
    Instant dueBefore = databaseClock.now().minus(Duration.ofDays(days));
    String reason = "Seit mehr als " + days + " Tagen ohne verantwortliche Stelle.";
    int locked = 0;
    UUID after = FIRST;
    while (true) {
      List<AssetResponsibility> page =
          repository.findPageAfter(after, PageRequest.of(0, PAGE_SIZE));
      if (page.isEmpty()) {
        return locked;
      }
      after = page.getLast().getAssetId();
      Map<UUID, Asset> assets =
          assetRepository
              .findAllById(page.stream().map(AssetResponsibility::getAssetId).toList())
              .stream()
              .collect(Collectors.toMap(Asset::getId, Function.identity()));
      Map<UUID, SuccessionFinding> succession =
          successionSource.findingsAmong(assets.values(), false);
      Set<UUID> groupIds =
          page.stream()
              .map(AssetResponsibility::getResponsibleGroupId)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());
      Map<UUID, Boolean> groupActs = service.groupsAct(groupIds);
      for (AssetResponsibility row : page) {
        Asset asset = assets.get(row.getAssetId());
        if (asset == null) {
          continue;
        }
        boolean missing =
            AssetResponsibilityService.isMissing(
                row, groupActs, succession.containsKey(asset.getId()));
        try {
          if (Boolean.TRUE.equals(
              transaction.execute(status -> decide(asset, row, missing, dueBefore, reason)))) {
            locked++;
          }
        } catch (RuntimeException e) {
          log.warn("Responsibility check of asset {} failed", asset.getId(), e);
        }
      }
    }
  }

  private boolean decide(
      Asset asset, AssetResponsibility row, boolean missing, Instant dueBefore, String reason) {
    if (!missing) {
      if (row.getMissingSince() != null) {
        repository.clearMissing(asset.getId());
      }
      return false;
    }
    if (row.getMissingSince() == null) {
      repository.markMissing(asset.getId());
      return false;
    }
    if (row.isLocked() || repository.lockForMissingUnit(asset.getId(), reason, dueBefore) == 0) {
      return false;
    }
    auditEventRecorder.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(asset.getOrganizationId())
            .actorRef(ACTOR)
            .type(AuditEventType.ASSET_LOCKED)
            .object(
                assetTypes.require(asset.getAssetType()).auditObjectType(),
                asset.getId(),
                asset.auditName())
            .after(asset.auditPayload(Map.of("cause", AssetLockCause.NO_RESPONSIBLE.name())))
            .reason(reason)
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return true;
  }
}
