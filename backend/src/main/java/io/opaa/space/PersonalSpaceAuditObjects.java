package io.opaa.space;

import io.opaa.api.types.AuditObjectType;
import io.opaa.audit.AuditNeutralLabels;
import io.opaa.audit.PersonalAuditObjects;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** The personal space of an account is a personal audit object (ADR-0055, Entscheidung 2). */
@Component
class PersonalSpaceAuditObjects implements PersonalAuditObjects {

  private final NamedParameterJdbcTemplate jdbc;

  PersonalSpaceAuditObjects(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<String, String> personal(AuditObjectType objectType, Collection<String> objectIds) {
    List<UUID> ids = PersonalAuditObjects.uuids(objectIds);
    if (objectType != AuditObjectType.SPACE || ids.isEmpty()) {
      return Map.of();
    }
    return jdbc
        .queryForList(
            "SELECT id FROM spaces WHERE is_default AND id IN (:ids)",
            Map.of("ids", ids),
            UUID.class)
        .stream()
        .collect(Collectors.toMap(UUID::toString, id -> AuditNeutralLabels.PERSONAL_SPACE));
  }
}
