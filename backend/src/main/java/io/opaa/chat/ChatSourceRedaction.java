package io.opaa.chat;

import io.opaa.knowledge.ErasedLibraryReferences;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Neutralizes the chat sources citing erased documents: each such source keeps only its rank, match
 * count and whether it was cited, and is named {@value #REMOVED}; file name, document, link,
 * locations and metadata go. The answer text itself stays with its chat.
 */
@Component
class ChatSourceRedaction implements ErasedLibraryReferences {

  static final String REMOVED = "Quelle entfernt";

  /** The messages of the organization whose sources name one of the documents. */
  private static final String CITING =
      " FROM chat_messages m JOIN chats c ON c.id = m.chat_id"
          + " WHERE c.organization_id = ? AND m.sources IS NOT NULL"
          + " AND EXISTS (SELECT 1 FROM json_array_elements(CASE WHEN json_typeof(m.sources) ="
          + " 'array' THEN m.sources ELSE '[]'::json END) s"
          + " WHERE s->>'documentId' = ANY (?))";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  ChatSourceRedaction(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public String countKey() {
    return "chatSourcesRedacted";
  }

  /** Whether the owner was ever cited from the library is usage, not proof of its erasure. */
  @Override
  public boolean inProof() {
    return false;
  }

  @Override
  public int remove(ErasedLibrary erased) {
    if (erased.documentIds().isEmpty()) {
      return 0;
    }
    String[] ids = idsOf(erased.documentIds());
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT m.id, m.sources::text AS sources" + CITING, erased.organizationId(), ids);
    for (Map<String, Object> row : rows) {
      jdbc.update(
          "UPDATE chat_messages SET sources = ?::json WHERE id = ?",
          redacted((String) row.get("sources"), List.of(ids)),
          row.get("id"));
    }
    return rows.size();
  }

  @Override
  public long remaining(ErasedLibrary erased) {
    if (erased.documentIds().isEmpty()) {
      return 0;
    }
    Long count =
        jdbc.queryForObject(
            "SELECT count(*)" + CITING,
            Long.class,
            erased.organizationId(),
            idsOf(erased.documentIds()));
    return count == null ? 0 : count;
  }

  private String redacted(String sources, List<String> erased) {
    ArrayNode array = (ArrayNode) objectMapper.readTree(sources);
    for (int index = 0; index < array.size(); index++) {
      JsonNode source = array.get(index);
      if (erased.contains(source.path("documentId").asString(""))) {
        ObjectNode neutral = objectMapper.createObjectNode();
        neutral.put("fileName", REMOVED);
        neutral.set("relevanceScore", source.path("relevanceScore"));
        neutral.set("matchCount", source.path("matchCount"));
        neutral.set("cited", source.path("cited"));
        if (source.has("privateSource")) {
          neutral.set("privateSource", source.get("privateSource"));
        }
        array.set(index, neutral);
      }
    }
    return objectMapper.writeValueAsString(array);
  }

  private static String[] idsOf(Collection<UUID> documentIds) {
    return documentIds.stream().map(UUID::toString).toArray(String[]::new);
  }
}
