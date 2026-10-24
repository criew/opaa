package io.opaa.audit;

import io.opaa.api.types.AuditObjectType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port to the modules that own audited objects: which of them belong to exactly one person (a
 * private library, a private prompt, the personal space). {@link AuditLogService} writes such an
 * object under its neutral label whatever the caller passed, and the SIEM export leaves out its id
 * and name (ADR-0055, Entscheidung 2) - one rule for every writer, present and future.
 */
public interface PersonalAuditObjects {

  /**
   * The neutral label ({@link AuditNeutralLabels}) of every id in {@code objectIds} of {@code
   * objectType} that belongs to one person; ids of other kinds or unknown ids are absent.
   */
  Map<String, String> personal(AuditObjectType objectType, Collection<String> objectIds);

  /** The ids of {@code objectIds} that are UUIDs; any other object id is no asset of a person. */
  static List<UUID> uuids(Collection<String> objectIds) {
    List<UUID> ids = new ArrayList<>();
    for (String id : objectIds) {
      if (id == null) {
        continue;
      }
      try {
        ids.add(UUID.fromString(id));
      } catch (IllegalArgumentException notAUuid) {
        // a fixed object id of a singleton names no asset
      }
    }
    return ids;
  }
}
