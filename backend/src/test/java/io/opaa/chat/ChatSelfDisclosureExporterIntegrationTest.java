package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.test.CapturingSelfDisclosureSink;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import io.opaa.test.SelfDisclosureTestData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The category {@code chats} against Postgres: only the person's own chats with marks and notes,
 * every message as a flat record with the AI marker, and a source reduced to title, library and
 * location while excerpt, document id and source links planted in the stored JSON stay out.
 */
@OpaaIntegrationTest
class ChatSelfDisclosureExporterIntegrationTest {

  private static final String EXCERPT = "SD-EXCERPT-7f3a Textausschnitt aus dem Dokument";

  @Autowired private ChatSelfDisclosureExporter exporter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnOrganizationFixtures organizations;

  private SelfDisclosureTestData data;
  private UUID person;
  private UUID other;
  private UUID space;

  @BeforeEach
  void setUp() {
    data = SelfDisclosureTestData.newOrganization(jdbc, "sd-chat-org");
    person = data.user("petra-person");
    other = data.user("otto-fremd");
    space = data.space("Fachbereich Bau", person);
  }

  @AfterEach
  void tearDown() {
    data.removeAll(organizations);
  }

  @Test
  void exportsOwnChatsWithMarksNotesAndMessagesAndSourcesOnlyAsTitleLibraryAndLocation()
      throws Exception {
    UUID library = data.library("Bibliothek Recht", person, false, "UPLOAD");
    UUID document =
        data.document(
            library, "dienstanweisung.pdf", "org/lib/stored-original", null, "UPLOAD", person, "x");
    UUID chat = chat("Mein Chat zur Dienstanweisung", person);
    jdbc.update(
        "UPDATE chats SET metadata_filter = '{\"document_type\":[\"ERLASS\"]}'::jsonb WHERE id = ?",
        chat);
    jdbc.update(
        "INSERT INTO chat_personal_marks (chat_id, user_id, organization_id, pinned_at)"
            + " VALUES (?, ?, ?, now())",
        chat,
        person,
        data.organizationId());
    note(chat, 0, "Nur Erlasse ab 2024");
    message(chat, 1, "USER", "Was regelt die Dienstanweisung?", null);
    message(
        chat,
        2,
        "ASSISTANT",
        "Sie regelt die Zeichnung.",
        "[{\"fileName\":\"dienstanweisung.pdf\",\"documentId\":\""
            + document
            + "\",\"relevanceScore\":1.0,\"matchCount\":2,\"cited\":true,"
            + "\"sourceUrl\":\"https://intern.example/SD-URL-geheim\","
            + "\"sourceEntryUrl\":\"https://intern.example/SD-ENTRY\","
            + "\"excerpt\":\""
            + EXCERPT
            + "\",\"text\":\""
            + EXCERPT
            + "\",\"chunkLocations\":[{\"chunkIndex\":9917,\"location\":\"S. 2–4\","
            + "\"text\":\""
            + EXCERPT
            + "\"}],\"metadata\":[{\"fieldKey\":\"title\",\"displayValue\":\""
            + EXCERPT
            + "\"}],\"freezeResponsible\":\"otto-fremd\"},"
            + "{\"fileName\":\"Quelle entfernt\",\"relevanceScore\":0.5,\"matchCount\":1,"
            + "\"cited\":false}]");

    CapturingSelfDisclosureSink sink = export();

    assertThat(sink.records("chats"))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.get("title")).isEqualTo("Mein Chat zur Dienstanweisung");
              assertThat(record.get("space")).isEqualTo("Fachbereich Bau");
              assertThat((String) record.get("filter")).contains("ERLASS");
              assertThat(record.get("pinnedAt")).isNotNull();
              assertThat(record.get("archivedAt")).isNull();
              assertThat(record.get("notes")).isEqualTo(List.of("Nur Erlasse ab 2024"));
            });
    List<Map<String, Object>> messages = sink.records("messages");
    assertThat(messages).hasSize(2);
    assertThat(messages.get(0))
        .containsEntry("role", "Frage")
        .containsEntry("generatedByAi", false)
        .containsEntry("content", "Was regelt die Dienstanweisung?")
        .containsEntry("chatTitle", "Mein Chat zur Dienstanweisung");
    assertThat(messages.get(1))
        .containsEntry("role", "Antwort")
        .containsEntry("generatedByAi", true)
        .containsEntry(
            "sources",
            List.of(
                "dienstanweisung.pdf · Bibliothek: Bibliothek Recht · S. 2–4 · zitiert",
                "Quelle entfernt"));
    assertThat(sink.text())
        .doesNotContain("SD-EXCERPT")
        .doesNotContain(document.toString())
        .doesNotContain("9917")
        .doesNotContain("SD-URL")
        .doesNotContain("SD-ENTRY")
        .doesNotContain("stored-original")
        .doesNotContain("otto");
  }

  @Test
  void leavesOutTheChatsOfAnotherPersonInTheSameSpace() throws Exception {
    UUID mine = chat("Mein Chat", person);
    message(mine, 1, "USER", "Meine Frage", null);
    UUID foreign = chat("Chat von Otto", other);
    message(foreign, 1, "USER", "SD-FOREIGN Frage von Otto", null);
    note(foreign, 0, "SD-FOREIGN Notiz");
    jdbc.update(
        "INSERT INTO chat_personal_marks (chat_id, user_id, organization_id, archived_at)"
            + " VALUES (?, ?, ?, now())",
        foreign,
        other,
        data.organizationId());

    CapturingSelfDisclosureSink sink = export();

    assertThat(sink.records("chats")).extracting(r -> r.get("chat")).containsExactly(mine);
    assertThat(sink.records("messages")).extracting(r -> r.get("chat")).containsExactly(mine);
    assertThat(sink.text())
        .doesNotContain("SD-FOREIGN")
        .doesNotContain("otto")
        .doesNotContain(other.toString())
        .doesNotContain(foreign.toString());
  }

  /** More chats and messages than one page each: all of them, in order, page after page. */
  @Test
  void pagesThroughManyChatsAndLongChatsCompletelyAndInOrder() throws Exception {
    int chats = ChatSelfDisclosureExporter.CHAT_PAGE_SIZE + 3;
    jdbc.update(
        "INSERT INTO chats (id, space_id, author_id, organization_id, use_knowledge, status,"
            + " title, created_at, updated_at) SELECT gen_random_uuid(), ?, ?, ?, true, 'PRIVATE',"
            + " 'Chat ' || n, now() - make_interval(secs => 10000 - n), now()"
            + " FROM generate_series(1, ?) n",
        space,
        person,
        data.organizationId(),
        chats);
    UUID longChat =
        jdbc.queryForObject(
            "SELECT id FROM chats WHERE title = 'Chat 5' AND organization_id = ?",
            UUID.class,
            data.organizationId());
    int messages = 2 * ChatSelfDisclosureExporter.MESSAGE_PAGE_SIZE + 7;
    jdbc.update(
        "INSERT INTO chat_messages (id, chat_id, sequence, role, content, created_at)"
            + " SELECT gen_random_uuid(), ?, n, CASE WHEN n % 2 = 1 THEN 'USER' ELSE 'ASSISTANT'"
            + " END, 'Nachricht ' || n, now() FROM generate_series(1, ?) n",
        longChat, messages);

    CapturingSelfDisclosureSink sink = export();

    assertThat(sink.records("chats")).hasSize(chats);
    assertThat(sink.records("chats").get(0)).containsEntry("title", "Chat 1");
    assertThat(sink.records("messages"))
        .hasSize(messages)
        .extracting(r -> r.get("sequence"))
        .isSorted()
        .doesNotHaveDuplicates();
  }

  @Test
  void aPersonWithoutChatsGetsNoSection() throws Exception {
    chat("Chat von Otto", other);

    CapturingSelfDisclosureSink sink = export();

    assertThat(sink.text()).isEqualTo("{}");
  }

  private CapturingSelfDisclosureSink export() throws Exception {
    CapturingSelfDisclosureSink sink =
        new CapturingSelfDisclosureSink(ChatSelfDisclosureExporter.CATEGORY);
    exporter.export(new SelfDisclosureSubject(person, data.organizationId()), sink);
    return sink;
  }

  private UUID chat(String title, UUID author) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO chats (id, space_id, author_id, organization_id, use_knowledge, status,"
            + " title, created_at, updated_at) VALUES (?, ?, ?, ?, true, 'PRIVATE', ?, now(),"
            + " now())",
        id,
        space,
        author,
        data.organizationId(),
        title);
    return id;
  }

  private void note(UUID chat, int position, String text) {
    jdbc.update(
        "INSERT INTO chat_note_items (id, chat_id, organization_id, position, text, kind,"
            + " created_at) VALUES (?, ?, ?, ?, ?, 'RAHMEN', now())",
        UUID.randomUUID(),
        chat,
        data.organizationId(),
        position,
        text);
  }

  private void message(UUID chat, int sequence, String role, String content, String sources) {
    jdbc.update(
        "INSERT INTO chat_messages (id, chat_id, sequence, role, content, sources, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?::json, now())",
        UUID.randomUUID(),
        chat,
        sequence,
        role,
        content,
        sources);
  }
}
