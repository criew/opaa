package io.opaa.library;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.LibraryConnectionRepository.PrivateStorageOnProfile;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.IndexingJobRepository.PrivateRunEnds;
import io.opaa.indexing.source.RunFailureCategory;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.PersonalStorageQuota;
import io.opaa.library.PrivateStorageSummary.MaskedNumber;
import io.opaa.library.PrivateStorageSummary.ProfileSum;
import io.opaa.library.PrivateStorageSummary.RunEnds;
import io.opaa.permission.PersonThreshold;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the system administration does with the storage quota across private libraries: read and set
 * the house-wide value, and see the private libraries of its organization in masked sums. No method
 * answers the use of one person.
 */
@Service
public class PrivateStorageAdministration {

  static final int RUN_WINDOW_DAYS = 30;

  private static final UUID SETTING_OBJECT_ID =
      UUID.nameUUIDFromBytes("private_storage_quota_settings".getBytes());

  private final PersonalStorageQuota quota;
  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryConnectionRepository connectionRepository;
  private final ConnectionProfileRepository profileRepository;
  private final IndexingJobRepository jobRepository;
  private final PrivateStorageMask mask;
  private final AuditEventRecorder auditEventRecorder;
  private final Clock clock;

  public PrivateStorageAdministration(
      PersonalStorageQuota quota,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      LibraryConnectionRepository connectionRepository,
      ConnectionProfileRepository profileRepository,
      IndexingJobRepository jobRepository,
      PersonThreshold threshold,
      AuditEventRecorder auditEventRecorder,
      Clock clock) {
    this.quota = quota;
    this.documentRepository = documentRepository;
    this.libraryRepository = libraryRepository;
    this.connectionRepository = connectionRepository;
    this.profileRepository = profileRepository;
    this.jobRepository = jobRepository;
    this.mask = new PrivateStorageMask(threshold);
    this.auditEventRecorder = auditEventRecorder;
    this.clock = clock;
  }

  /** The value in force, the configured default and whether the administration set its own. */
  public record QuotaSetting(long quotaBytes, long defaultQuotaBytes, boolean overridden) {}

  @Transactional(readOnly = true)
  public QuotaSetting read(CurrentUser actor) {
    requireSystemAdmin(actor);
    return current();
  }

  /** Sets the house-wide value; {@code null} returns to the default, {@code 0} is unlimited. */
  @Transactional
  public QuotaSetting change(CurrentUser actor, Long quotaBytes) {
    requireSystemAdmin(actor);
    if (quotaBytes != null && quotaBytes < 0) {
      throw new ValidationException("Das Speicherkontingent darf nicht negativ sein");
    }
    QuotaSetting before = current();
    quota.setQuotaBytes(quotaBytes);
    QuotaSetting after = current();
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.PRIVATE_STORAGE_QUOTA_CHANGED)
            .object(AuditObjectType.SYSTEM_SETTING, SETTING_OBJECT_ID, null)
            .before(payload(before))
            .after(payload(after))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return after;
  }

  /** The private libraries of the caller's organization in masked sums. */
  @Transactional(readOnly = true)
  public PrivateStorageSummary summary(CurrentUser actor) {
    requireSystemAdmin(actor);
    UUID organizationId = actor.organizationId();
    long owners = libraryRepository.countPrivateLibraryOwners(organizationId);

    Map<UUID, PrivateStorageOnProfile> onProfile = new HashMap<>();
    for (PrivateStorageOnProfile row :
        connectionRepository.sumPrivateStorageByProfile(organizationId)) {
      onProfile.put(row.getProfileId(), row);
    }
    Map<UUID, Long> personsOn = new HashMap<>();
    onProfile.forEach((profileId, row) -> personsOn.put(profileId, row.getOwners()));
    Map<UUID, List<UUID>> librariesOn = new HashMap<>();
    Set<UUID> disclosed =
        mask.disclosedParts(
            personsOn,
            profileIds -> ownersOutside(organizationId, owners, profileIds, librariesOn));

    List<ProfileSum> profiles = new ArrayList<>();
    for (ConnectionProfile profile : profileRepository.findAllByOrderByNameAsc()) {
      if (!profile.getOwnership().admitsPersons()) {
        continue;
      }
      PrivateStorageOnProfile row = onProfile.get(profile.getId());
      MaskedNumber used =
          row != null && disclosed.contains(profile.getId())
              ? MaskedNumber.exact(row.getBytes())
              : mask.withheld(row == null ? 0 : row.getOwners());
      profiles.add(new ProfileSum(profile.getId(), profile.getName(), used));
    }

    Map<String, PrivateRunEnds> ended = new HashMap<>();
    for (PrivateRunEnds row :
        jobRepository.countPrivateRunEndsSince(
            organizationId, clock.instant().minus(Duration.ofDays(RUN_WINDOW_DAYS)))) {
      ended.put(row.getCategory(), row);
    }
    List<RunEnds> runEnds = new ArrayList<>();
    for (RunFailureCategory category : RunFailureCategory.values()) {
      PrivateRunEnds row = ended.get(category.name());
      long runs = row == null ? 0 : row.getRuns();
      long persons = row == null ? 0 : row.getOwners();
      runEnds.add(new RunEnds(category.name(), mask.part(runs, persons, owners - persons)));
    }

    return new PrivateStorageSummary(
        quota.quotaBytes(),
        mask.total(owners, owners),
        mask.total(documentRepository.sumFileSizeOfPrivateLibraries(organizationId), owners),
        profiles,
        RUN_WINDOW_DAYS,
        runEnds);
  }

  /** Persons owning a private library of the organization outside the given profiles. */
  private long ownersOutside(
      UUID organizationId,
      long allOwners,
      Set<UUID> profileIds,
      Map<UUID, List<UUID>> librariesOn) {
    Set<UUID> excluded = new HashSet<>();
    for (UUID profileId : profileIds) {
      excluded.addAll(
          librariesOn.computeIfAbsent(
              profileId, id -> connectionRepository.privateLibraryIdsOn(id, organizationId)));
    }
    return excluded.isEmpty()
        ? allOwners
        : libraryRepository.countPrivateLibraryOwnersOutside(organizationId, excluded);
  }

  private QuotaSetting current() {
    return new QuotaSetting(quota.quotaBytes(), quota.defaultQuotaBytes(), quota.isOverridden());
  }

  private static Map<String, Object> payload(QuotaSetting setting) {
    return Map.of("quotaBytes", setting.quotaBytes(), "overridden", setting.overridden());
  }

  private static void requireSystemAdmin(CurrentUser actor) {
    if (!actor.isSystemAdmin()) {
      throw new AccessDeniedException(
          "Nur die Systemverwaltung darf das Speicherkontingent privater Bibliotheken einsehen"
              + " und ändern");
    }
  }
}
