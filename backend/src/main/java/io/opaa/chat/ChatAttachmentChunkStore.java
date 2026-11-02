package io.opaa.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The text of chat attachments as the format pipeline chunked it, one row per chunk with its
 * metadata (Fundort, anchor). Removed with its attachment by {@code ON DELETE CASCADE}.
 */
@Component
class ChatAttachmentChunkStore {

  private static final TypeReference<Map<String, Object>> METADATA = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  ChatAttachmentChunkStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** One chunk: its position in the document, its text and the pipeline's metadata. */
  record Chunk(UUID attachmentId, int index, String text, Map<String, Object> metadata) {}

  @Transactional
  void save(UUID attachmentId, List<Chunk> chunks) {
    jdbc.batchUpdate(
        "INSERT INTO chat_attachment_chunks (attachment_id, chunk_index, content, metadata)"
            + " VALUES (?, ?, ?, ?::jsonb)",
        chunks,
        200,
        (statement, chunk) -> {
          statement.setObject(1, attachmentId);
          statement.setInt(2, chunk.index());
          statement.setString(3, chunk.text());
          statement.setString(4, objectMapper.writeValueAsString(chunk.metadata()));
        });
  }

  /** The chunks of {@code attachmentIds}, grouped by attachment and in document order. */
  Map<UUID, List<Chunk>> load(Collection<UUID> attachmentIds) {
    Map<UUID, List<Chunk>> byAttachment = new LinkedHashMap<>();
    if (attachmentIds.isEmpty()) {
      return byAttachment;
    }
    jdbc.query(
        "SELECT attachment_id, chunk_index, content, metadata::text AS metadata"
            + " FROM chat_attachment_chunks WHERE attachment_id = ANY (?::uuid[])"
            + " ORDER BY attachment_id, chunk_index",
        row -> {
          UUID attachmentId = row.getObject("attachment_id", UUID.class);
          String json = row.getString("metadata");
          Map<String, Object> metadata =
              json == null ? new HashMap<>() : objectMapper.readValue(json, METADATA);
          byAttachment
              .computeIfAbsent(attachmentId, id -> new ArrayList<>())
              .add(
                  new Chunk(
                      attachmentId, row.getInt("chunk_index"), row.getString("content"), metadata));
        },
        (Object) attachmentIds.stream().map(UUID::toString).toArray(String[]::new));
    return byAttachment;
  }
}
