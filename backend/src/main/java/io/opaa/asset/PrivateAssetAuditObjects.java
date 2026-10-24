package io.opaa.asset;

import io.opaa.api.types.AuditObjectType;
import io.opaa.audit.AuditNeutralLabels;
import io.opaa.audit.PersonalAuditObjects;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Owner-only knowledge and prompt libraries are personal audit objects (ADR-0041, ADR-0055). */
@Component
class PrivateAssetAuditObjects implements PersonalAuditObjects {

  private static final Set<AuditObjectType> TYPES =
      Set.of(AuditObjectType.KNOWLEDGE_LIBRARY, AuditObjectType.PROMPT_LIBRARY);

  private final NamedParameterJdbcTemplate jdbc;

  PrivateAssetAuditObjects(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<String, String> personal(AuditObjectType objectType, Collection<String> objectIds) {
    List<UUID> ids = PersonalAuditObjects.uuids(objectIds);
    if (!TYPES.contains(objectType) || ids.isEmpty()) {
      return Map.of();
    }
    return jdbc
        .queryForList(
            "SELECT id FROM assets WHERE owner_only AND id IN (:ids)",
            Map.of("ids", ids),
            UUID.class)
        .stream()
        .collect(Collectors.toMap(UUID::toString, id -> AuditNeutralLabels.PRIVATE_ASSET));
  }
}
