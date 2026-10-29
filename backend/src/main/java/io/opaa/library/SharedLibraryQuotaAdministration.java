package io.opaa.library;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ByteSizes;
import io.opaa.common.FieldValidationException;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SharedLibraryQuota;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the system administration does with the storage quota per shared library: read it with the
 * operator's upper limit and the number of shared libraries above it, and set the house-wide value
 * within that limit (ADR-0060, Entscheidung 2). Nothing already stored is removed.
 */
@Service
public class SharedLibraryQuotaAdministration {

  static final String FIELD = "quotaBytes";

  private static final String ADMIN_ONLY =
      "Nur die Systemverwaltung darf das Speicherkontingent geteilter Bibliotheken einsehen"
          + " und ändern";

  private static final UUID SETTING_OBJECT_ID =
      UUID.nameUUIDFromBytes("shared_library_quota_settings".getBytes(StandardCharsets.UTF_8));

  private final SharedLibraryQuota quota;
  private final KnowledgeLibraryRepository libraryRepository;
  private final AuditEventRecorder auditEventRecorder;

  public SharedLibraryQuotaAdministration(
      SharedLibraryQuota quota,
      KnowledgeLibraryRepository libraryRepository,
      AuditEventRecorder auditEventRecorder) {
    this.quota = quota;
    this.libraryRepository = libraryRepository;
    this.auditEventRecorder = auditEventRecorder;
  }

  /**
   * The value in force ({@code 0} unlimited), the own value or {@code null}, the operator's upper
   * limit ({@code 0} none) and how many shared libraries of the organization occupy more already.
   */
  public record QuotaSetting(
      long quotaBytes, Long ownQuotaBytes, long capBytes, long librariesOverQuota) {}

  @Transactional(readOnly = true)
  public QuotaSetting read(CurrentUser actor) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    return current(actor);
  }

  /**
   * Sets the house-wide value, at least one byte and at most the upper limit; {@code null} removes
   * it. The value already set writes nothing and records nothing.
   */
  @Transactional
  public QuotaSetting change(CurrentUser actor, Long quotaBytes) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    long cap = quota.capBytes();
    if (quotaBytes != null && quotaBytes < 1) {
      throw FieldValidationException.of(
          FIELD, "INVALID", "Das Speicherkontingent muss größer als 0 sein.");
    }
    if (quotaBytes != null && cap > 0 && quotaBytes > cap) {
      throw FieldValidationException.of(
          FIELD,
          "ABOVE_LIMIT",
          "Das Speicherkontingent darf die Obergrenze des Betriebs von "
              + ByteSizes.format(cap)
              + " nicht überschreiten.");
    }
    Long ownBefore = quota.ownQuotaBytes();
    if (Objects.equals(ownBefore, quotaBytes)) {
      return current(actor);
    }
    long inForceBefore = quota.quotaBytes();
    quota.setOwnQuotaBytes(quotaBytes);
    QuotaSetting after = current(actor);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.SHARED_LIBRARY_QUOTA_CHANGED)
            .object(AuditObjectType.SYSTEM_SETTING, SETTING_OBJECT_ID, null)
            .before(payload(ownBefore, inForceBefore))
            .after(payload(after.ownQuotaBytes(), after.quotaBytes()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return after;
  }

  private QuotaSetting current(CurrentUser actor) {
    long inForce = quota.quotaBytes();
    long above =
        inForce > 0
            ? libraryRepository.countSharedLibrariesAbove(actor.organizationId(), inForce)
            : 0;
    return new QuotaSetting(inForce, quota.ownQuotaBytes(), quota.capBytes(), above);
  }

  private static Map<String, Object> payload(Long ownQuotaBytes, long quotaBytes) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ownQuotaBytes", ownQuotaBytes);
    payload.put("quotaBytes", quotaBytes);
    return payload;
  }
}
