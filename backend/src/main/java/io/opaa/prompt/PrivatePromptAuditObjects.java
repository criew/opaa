package io.opaa.prompt;

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

/** A prompt in a private prompt library is a personal audit object (ADR-0055, Entscheidung 2). */
@Component
class PrivatePromptAuditObjects implements PersonalAuditObjects {

  private final NamedParameterJdbcTemplate jdbc;

  PrivatePromptAuditObjects(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<String, String> personal(AuditObjectType objectType, Collection<String> objectIds) {
    List<UUID> ids = PersonalAuditObjects.uuids(objectIds);
    if (objectType != AuditObjectType.PROMPT || ids.isEmpty()) {
      return Map.of();
    }
    return jdbc
        .queryForList(
            "SELECT p.id FROM prompts p JOIN assets a ON a.id = p.library_id"
                + " WHERE a.owner_only AND p.id IN (:ids)",
            Map.of("ids", ids),
            UUID.class)
        .stream()
        .collect(Collectors.toMap(UUID::toString, id -> AuditNeutralLabels.PRIVATE_PROMPT));
  }
}
