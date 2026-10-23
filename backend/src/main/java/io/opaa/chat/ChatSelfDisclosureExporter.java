package io.opaa.chat;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSink.RecordWriter;
import io.opaa.compliance.SelfDisclosureSubject;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The category {@code chats} of the self-disclosure: the person's own chats ({@code author_id})
 * with their marks and notes, then every message as one flat record. A source of an answer goes out
 * only as document title, library and location ({@link #sourceLine}), built from a positive list of
 * the stored JSON, never its excerpt, chunk or document id. Chats are read in pages, the messages
 * of a chat in pages of their sequence, each page in a short read-only transaction.
 */
@Component
public class ChatSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "chats",
          "Chats und Nachrichten",
          List.of(
              new Section(
                  "chats",
                  "Ihre Chats",
                  List.of(
                      Field.of("chat", "Chat-Kennung", "chats.id"),
                      Field.of("title", "Titel", "chats.title"),
                      Field.of("space", "Space", "spaces.name"),
                      Field.of("filter", "Filter", "chats.metadata_filter"),
                      Field.of("createdAt", "Angelegt", "chats.created_at"),
                      Field.of("updatedAt", "Zuletzt geändert", "chats.updated_at"),
                      Field.of("pinnedAt", "Angeheftet", "chat_personal_marks.pinned_at"),
                      Field.of("archivedAt", "Archiviert", "chat_personal_marks.archived_at"),
                      Field.of(
                          "unarchivedAt",
                          "Aus dem Archiv geholt",
                          "chat_personal_marks.unarchived_at"),
                      Field.of("notes", "Notizen", "chat_note_items.text"))),
              new Section(
                  "messages",
                  "Nachrichten",
                  List.of(
                      Field.of("chat", "Chat-Kennung", "chats.id"),
                      Field.of("chatTitle", "Chat", "chats.title"),
                      Field.of("sequence", "Nr.", "chat_messages.sequence"),
                      Field.of("role", "Art", "chat_messages.role"),
                      Field.derived("generatedByAi", "KI-erzeugt", "chat_messages.role"),
                      Field.of("content", "Text", "chat_messages.content"),
                      Field.of(
                          "usedPrompt", "Verwendeter Prompt", "chat_messages.used_prompt_title"),
                      Field.of("createdAt", "Zeitpunkt", "chat_messages.created_at"),
                      Field.of(
                          "sources",
                          "Fundstellen (Titel, Bibliothek, Ort)",
                          "chat_messages.sources",
                          "assets.name")))));

  static final int CHAT_PAGE_SIZE = 200;
  static final int MESSAGE_PAGE_SIZE = 200;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnly;
  private final JsonMapper json = JsonMapper.builder().build();

  public ChatSelfDisclosureExporter(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.readOnly = new TransactionTemplate(transactionManager);
    this.readOnly.setReadOnly(true);
  }

  @Override
  public SelfDisclosureCategory category() {
    return CATEGORY;
  }

  @Override
  public void export(SelfDisclosureSubject subject, SelfDisclosureSink sink) throws IOException {
    RecordWriter chats = null;
    ChatCursor cursor = ChatCursor.START;
    while (true) {
      ChatCursor after = cursor;
      List<Map<String, Object>> page = readOnly.execute(s -> chatPage(subject, after));
      if (page == null || page.isEmpty()) {
        break;
      }
      if (chats == null) {
        chats = sink.section("chats");
      }
      for (Map<String, Object> chat : page) {
        cursor = new ChatCursor((Instant) chat.get("createdAt"), (UUID) chat.get("chat"));
        chats.write(chat);
      }
      if (page.size() < CHAT_PAGE_SIZE) {
        break;
      }
    }
    if (chats != null) {
      writeMessages(subject, sink.section("messages"));
    }
  }

  private void writeMessages(SelfDisclosureSubject subject, RecordWriter messages)
      throws IOException {
    ChatCursor cursor = ChatCursor.START;
    while (true) {
      ChatCursor after = cursor;
      List<ChatHead> page = readOnly.execute(s -> chatHeads(subject, after));
      if (page == null || page.isEmpty()) {
        return;
      }
      for (ChatHead chat : page) {
        writeMessagesOf(chat, subject, messages);
        cursor = new ChatCursor(chat.createdAt(), chat.id());
      }
      if (page.size() < CHAT_PAGE_SIZE) {
        return;
      }
    }
  }

  private void writeMessagesOf(ChatHead chat, SelfDisclosureSubject subject, RecordWriter messages)
      throws IOException {
    int afterSequence = Integer.MIN_VALUE;
    while (true) {
      int after = afterSequence;
      List<Map<String, Object>> page = readOnly.execute(s -> messagePage(chat, subject, after));
      if (page == null || page.isEmpty()) {
        return;
      }
      for (Map<String, Object> message : page) {
        afterSequence = (Integer) message.get("sequence");
        messages.write(message);
      }
      if (page.size() < MESSAGE_PAGE_SIZE) {
        return;
      }
    }
  }

  private List<Map<String, Object>> chatPage(SelfDisclosureSubject subject, ChatCursor after) {
    List<Map<String, Object>> page =
        jdbc.query(
            "SELECT c.id, c.title, s.name AS space_name, c.metadata_filter::text AS filter,"
                + " c.created_at, c.updated_at, k.pinned_at, k.archived_at, k.unarchived_at"
                + " FROM chats c LEFT JOIN spaces s ON s.id = c.space_id"
                + " LEFT JOIN chat_personal_marks k ON k.chat_id = c.id AND k.user_id = c.author_id"
                + " WHERE c.author_id = ? AND c.organization_id = ?"
                + " AND (c.created_at, c.id) > (?, ?) ORDER BY c.created_at, c.id LIMIT ?",
            (rs, i) -> {
              Map<String, Object> record = new LinkedHashMap<>();
              record.put("chat", rs.getObject("id", UUID.class));
              record.put("title", rs.getString("title"));
              record.put("space", rs.getString("space_name"));
              record.put("filter", rs.getString("filter"));
              record.put("createdAt", instant(rs, "created_at"));
              record.put("updatedAt", instant(rs, "updated_at"));
              record.put("pinnedAt", instant(rs, "pinned_at"));
              record.put("archivedAt", instant(rs, "archived_at"));
              record.put("unarchivedAt", instant(rs, "unarchived_at"));
              return record;
            },
            subject.accountId(),
            subject.organizationId(),
            Timestamp.from(after.createdAt()),
            after.id(),
            CHAT_PAGE_SIZE);
    if (page.isEmpty()) {
      return page;
    }
    Map<UUID, List<String>> notes = new HashMap<>();
    jdbc.query(
        "SELECT chat_id, text FROM chat_note_items WHERE chat_id = ANY (?)"
            + " ORDER BY chat_id, position",
        rs -> {
          notes
              .computeIfAbsent(rs.getObject("chat_id", UUID.class), id -> new ArrayList<>())
              .add(rs.getString("text"));
        },
        (Object) page.stream().map(r -> r.get("chat")).toArray(UUID[]::new));
    for (Map<String, Object> record : page) {
      record.put("notes", notes.getOrDefault((UUID) record.get("chat"), List.of()));
    }
    return page;
  }

  private List<ChatHead> chatHeads(SelfDisclosureSubject subject, ChatCursor after) {
    return jdbc.query(
        "SELECT id, title, created_at FROM chats WHERE author_id = ? AND organization_id = ?"
            + " AND (created_at, id) > (?, ?) ORDER BY created_at, id LIMIT ?",
        (rs, i) ->
            new ChatHead(
                rs.getObject("id", UUID.class), rs.getString("title"), instant(rs, "created_at")),
        subject.accountId(),
        subject.organizationId(),
        Timestamp.from(after.createdAt()),
        after.id(),
        CHAT_PAGE_SIZE);
  }

  private List<Map<String, Object>> messagePage(
      ChatHead chat, SelfDisclosureSubject subject, int afterSequence) {
    List<StoredMessage> stored =
        jdbc.query(
            "SELECT m.sequence, m.role, m.content, m.used_prompt_title, m.created_at,"
                + " m.sources::text AS sources FROM chat_messages m JOIN chats c ON c.id ="
                + " m.chat_id WHERE m.chat_id = ? AND c.author_id = ? AND c.organization_id = ?"
                + " AND m.sequence > ? ORDER BY m.sequence LIMIT ?",
            (rs, i) ->
                new StoredMessage(
                    rs.getInt("sequence"),
                    rs.getString("role"),
                    rs.getString("content"),
                    rs.getString("used_prompt_title"),
                    instant(rs, "created_at"),
                    sourcesOf(rs.getString("sources"))),
            chat.id(),
            subject.accountId(),
            subject.organizationId(),
            afterSequence,
            MESSAGE_PAGE_SIZE);
    Map<UUID, String> libraries = libraryNames(stored, subject);
    List<Map<String, Object>> page = new ArrayList<>(stored.size());
    for (StoredMessage message : stored) {
      boolean answer = "ASSISTANT".equals(message.role());
      Map<String, Object> record = new LinkedHashMap<>();
      record.put("chat", chat.id());
      record.put("chatTitle", chat.title());
      record.put("sequence", message.sequence());
      record.put("role", answer ? "Antwort" : "Frage");
      record.put("generatedByAi", answer);
      record.put("content", message.content());
      record.put("usedPrompt", message.usedPrompt());
      record.put("createdAt", message.createdAt());
      record.put(
          "sources",
          message.sources().stream()
              .map(source -> sourceLine(source, libraries.get(source.documentId())))
              .toList());
      page.add(record);
    }
    return page;
  }

  /** The current library name of every document a source of {@code messages} names. */
  private Map<UUID, String> libraryNames(
      List<StoredMessage> messages, SelfDisclosureSubject subject) {
    Set<UUID> documents = new LinkedHashSet<>();
    messages.forEach(
        m ->
            m.sources().stream()
                .map(StoredSource::documentId)
                .filter(id -> id != null)
                .forEach(documents::add));
    Map<UUID, String> names = new HashMap<>();
    if (documents.isEmpty()) {
      return names;
    }
    jdbc.query(
        "SELECT d.id, a.name FROM documents d JOIN assets a ON a.id = d.library_id"
            + " WHERE d.id = ANY (?) AND d.organization_id = ?",
        rs -> {
          names.put(rs.getObject("id", UUID.class), rs.getString("name"));
        },
        documents.toArray(UUID[]::new),
        subject.organizationId());
    return names;
  }

  /**
   * The positive list of a stored source: file name, document id (only to look up the library,
   * never written), the locations and whether it was cited. Every other property of the JSON, an
   * excerpt among them, is never read.
   */
  List<StoredSource> sourcesOf(String sources) {
    if (sources == null || sources.isBlank()) {
      return List.of();
    }
    JsonNode array = json.readTree(sources);
    if (!array.isArray()) {
      return List.of();
    }
    List<StoredSource> result = new ArrayList<>();
    for (JsonNode source : array) {
      List<String> locations = new ArrayList<>();
      for (JsonNode location : source.path("chunkLocations")) {
        String text = location.path("location").asString(null);
        if (text != null && !text.isBlank() && !locations.contains(text)) {
          locations.add(text);
        }
      }
      result.add(
          new StoredSource(
              source.path("fileName").asString(null),
              uuidOrNull(source.path("documentId").asString(null)),
              List.copyOf(locations),
              source.path("cited").asBoolean(false)));
    }
    return result;
  }

  /** One source as the export shows it: title, library and location, cited or not. */
  static String sourceLine(StoredSource source, String library) {
    StringBuilder line =
        new StringBuilder(source.fileName() == null ? "ohne Titel" : source.fileName());
    if (library != null) {
      line.append(" · Bibliothek: ").append(library);
    }
    if (!source.locations().isEmpty()) {
      line.append(" · ").append(String.join(", ", source.locations()));
    }
    if (source.cited()) {
      line.append(" · zitiert");
    }
    return line.toString();
  }

  private static UUID uuidOrNull(String value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException notAnId) {
      return null;
    }
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private record ChatCursor(Instant createdAt, UUID id) {
    static final ChatCursor START = new ChatCursor(Instant.EPOCH.minusSeconds(1), new UUID(0, 0));
  }

  private record ChatHead(UUID id, String title, Instant createdAt) {}

  private record StoredMessage(
      int sequence,
      String role,
      String content,
      String usedPrompt,
      Instant createdAt,
      List<StoredSource> sources) {}

  record StoredSource(String fileName, UUID documentId, List<String> locations, boolean cited) {}
}
