package io.opaa.audit;

import io.opaa.api.types.AuditEventType;
import java.util.Set;

/**
 * Where an entry names an owner it carries neither as actor nor as subject: under {@link #KEY} in
 * {@code before}/{@code after}, as pseudonym (entries written before owners were pseudonymised: as
 * account id) or, for an owning group, as group id. The self-disclosure and the anlassbezogene
 * Klärung search there for the event types of {@link #EVENT_TYPES} only.
 */
final class AuditOwnerPayload {

  static final String KEY = "ownerId";

  static final Set<AuditEventType> EVENT_TYPES =
      Set.of(
          AuditEventType.SPACE_CREATED,
          AuditEventType.SPACE_DELETED,
          AuditEventType.SPACE_ARCHIVED,
          AuditEventType.ASSET_OWNER_CHANGED,
          AuditEventType.ASSET_OWNERSHIP_CLAIMED,
          AuditEventType.ASSET_SUCCESSION_OPENED);

  private AuditOwnerPayload() {}

  /**
   * A {@code LIKE} pattern matching a stored payload that names {@code ref} as owner. The recorder
   * writes compact JSON, and a quote inside a string value is escaped, so only the key itself
   * matches; a pseudonym or account id holds no {@code LIKE} wildcard.
   */
  static String ownerPattern(String ref) {
    return "%\"" + KEY + "\":\"" + ref + "\"%";
  }
}
