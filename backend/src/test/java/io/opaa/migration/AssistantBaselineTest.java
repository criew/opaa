package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the baseline's assistant changeSet: chats with their messages and full-text index,
 * the note items of a conversation, the personal marks of the chat list, and the prompt libraries
 * as the second asset type.
 */
class AssistantBaselineTest extends AbstractBaselineTest {

  // ---------------------------------------------------------------------------------------------
  // Messages
  // ---------------------------------------------------------------------------------------------

  /** The generated column indexes every message with German stemming and cannot be written. */
  @Test
  void everyMessageIsIndexedWithGermanStemmingAndTheIndexColumnIsNotWritable() throws SQLException {
    UUID chat = insertChat(insertUser());
    execute(messageSql(chat, 1, "USER", "Welche Gebühren gelten für Hundesteuern?", null, null));

    assertThat(
            longOf(
                "SELECT count(*) FROM chat_messages WHERE content_tsv @@"
                    + " to_tsquery('german', 'Gebühr & Hundesteuer')"))
        .isEqualTo(1);
    assertRejected(
        "UPDATE chat_messages SET content_tsv = to_tsvector('german', 'anders')", "content_tsv");
  }

  /** A prompt used for a question is recorded at the question only, with id and title together. */
  @Test
  void onlyAUserMessageNamesTheUsedPromptAndAlwaysWithIdAndTitle() throws SQLException {
    UUID chat = insertChat(insertUser());
    UUID promptThatNoLongerExists = UUID.randomUUID();
    execute(messageSql(chat, 1, "USER", "Frage", promptThatNoLongerExists, "'Bescheid prüfen'"));

    assertRejected(
        messageSql(chat, 2, "USER", "Frage", UUID.randomUUID(), "NULL"),
        "chk_chat_messages_used_prompt");
    assertRejected(
        messageSql(chat, 3, "USER", "Frage", null, "'Titel'"), "chk_chat_messages_used_prompt");
    assertRejected(
        messageSql(chat, 4, "ASSISTANT", "Antwort", UUID.randomUUID(), "'Titel'"),
        "chk_chat_messages_used_prompt");
  }

  @Test
  void deletingAChatTakesItsMessagesNotesAndMarksWithIt() throws SQLException {
    UUID user = insertUser();
    UUID chat = insertChat(user);
    execute(messageSql(chat, 1, "USER", "Frage", null, null));
    execute(noteSql(chat, 0, "RAHMEN", "Bezugsjahr 2024"));
    execute(markSql(chat, user, "now()", "NULL"));

    execute("DELETE FROM chats WHERE id = '" + chat + "'");

    for (String table : new String[] {"chat_messages", "chat_note_items", "chat_personal_marks"}) {
      assertThat(countRows(table)).as(table).isZero();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Note items
  // ---------------------------------------------------------------------------------------------

  /**
   * The application never names the organization of a note item: a trigger takes it from the chat,
   * and even an explicitly foreign one is overwritten.
   */
  @Test
  void aNoteItemTakesItsOrganizationFromItsChat() throws SQLException {
    UUID chat = insertChat(insertUser());
    UUID otherOrganization = insertOrganization();
    execute(
        "INSERT INTO chat_note_items (id, chat_id, organization_id, position, text, kind) VALUES"
            + " (gen_random_uuid(), '"
            + chat
            + "', '"
            + otherOrganization
            + "', 0, 'Bezugsjahr 2024', 'RAHMEN')");
    execute(noteSql(chat, 1, "ANTWORTFORM", "Tabellarisch"));

    assertThat(countWhere("chat_note_items", "organization_id = '" + SEEDED_ORGANIZATION_ID + "'"))
        .isEqualTo(2);
  }

  @Test
  void aNoteItemHasAKnownKindAndAnUnsharedNonNegativePositionAndAtMost200Characters()
      throws SQLException {
    UUID chat = insertChat(insertUser());
    execute(noteSql(chat, 0, "RAHMEN", "x".repeat(200)));

    assertRejected(noteSql(chat, 0, "RAHMEN", "zweiter Punkt"), "uk_chat_note_items_chat_position");
    assertRejected(noteSql(chat, -1, "RAHMEN", "negativ"), "chk_chat_note_items_position");
    assertRejected(noteSql(chat, 2, "WUNSCH", "unbekannt"), "chk_chat_note_items_kind");
    assertRejected(noteSql(chat, 3, "RAHMEN", "x".repeat(201)), "value too long");
  }

  // ---------------------------------------------------------------------------------------------
  // Personal marks
  // ---------------------------------------------------------------------------------------------

  /**
   * The marks belong to the person: at most one row per person and chat, its organization taken
   * from the chat, and a person of another organization cannot mark it at all.
   */
  @Test
  void aPersonMarksAChatOnceWithinTheChatsOrganization() throws SQLException {
    UUID user = insertUser();
    UUID chat = insertChat(user);
    execute(markSql(chat, user, "NULL", "NULL"));

    assertThat(
            countWhere(
                "chat_personal_marks",
                "pinned_at IS NULL AND organization_id = '" + SEEDED_ORGANIZATION_ID + "'"))
        .isEqualTo(1);
    assertRejected(markSql(chat, user, "now()", "NULL"), "chat_personal_marks_pkey");
    UUID stranger = insertUser(insertOrganization());
    assertRejected(
        markSql(chat, stranger, "now()", "NULL"), "fk_chat_personal_marks_user_organization");
  }

  @Test
  void aChatIsPinnedOrArchivedButNeverBoth() throws SQLException {
    UUID user = insertUser();
    execute(markSql(insertChat(user), user, "NULL", "now()"));
    execute(markSql(insertChat(user), user, "now()", "NULL"));

    assertRejected(
        markSql(insertChat(user), user, "now()", "now()"),
        "chk_chat_personal_marks_not_pinned_and_archived");
  }

  @Test
  void deletingTheAccountTakesItsMarksWithIt() throws SQLException {
    UUID author = insertUser();
    UUID reader = insertUser();
    UUID chat = insertChat(author);
    execute(markSql(chat, reader, "now()", "NULL"));

    execute("DELETE FROM users WHERE id = '" + reader + "'");

    assertThat(countRows("chat_personal_marks")).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // Prompt libraries
  // ---------------------------------------------------------------------------------------------

  /** The type row stands only next to a shell of its own type, and goes with it and its prompts. */
  @Test
  void aPromptLibraryStandsOnItsOwnShellAndGoesWithItAndItsPrompts() throws SQLException {
    UUID owner = insertUser();
    UUID knowledgeShell = insertAsset("KNOWLEDGE_LIBRARY", owner);

    assertRejected(promptLibrarySql(knowledgeShell), "fk_prompt_libraries_asset");
    assertRejected(promptLibrarySql(UUID.randomUUID()), "fk_prompt_libraries_asset");
    assertRejected(
        "INSERT INTO prompt_libraries (id, asset_type, organization_id) VALUES ('"
            + knowledgeShell
            + "', 'KNOWLEDGE_LIBRARY', '"
            + SEEDED_ORGANIZATION_ID
            + "')",
        "chk_prompt_libraries_asset_type");

    UUID library = insertPromptLibrary(owner);
    execute(promptSql(library, "bescheid-pruefen", "Text"));
    execute("DELETE FROM assets WHERE id = '" + library + "'");
    assertThat(countRows("prompt_libraries")).isZero();
    assertThat(countRows("prompts")).isZero();
  }

  /** The name is the slash command: unique per library, lower case, digits and single hyphens. */
  @Test
  void aPromptNameIsUniquePerLibraryAndShapedLikeASlashCommand() throws SQLException {
    UUID library = insertPromptLibrary(insertUser());
    execute(promptSql(library, "bescheid-pruefen-2", "Text"));
    execute(promptSql(insertPromptLibrary(insertUser()), "bescheid-pruefen-2", "Text"));

    assertRejected(promptSql(library, "bescheid-pruefen-2", "Text"), "uk_prompts_library_name");
    for (String name : new String[] {"Bescheid", "bescheid--pruefen", "-bescheid", "bescheid_1"}) {
      assertRejected(promptSql(library, name, "Text"), "chk_prompts_name_format");
    }
  }

  @Test
  void aPromptTextHoldsOneTo8000CharactersAndItsVariablesAreAnArray() throws SQLException {
    UUID library = insertPromptLibrary(insertUser());
    execute(promptSql(library, "lang", "x".repeat(8000)));

    assertRejected(promptSql(library, "zu-lang", "x".repeat(8001)), "chk_prompts_text_length");
    assertRejected(promptSql(library, "leer", ""), "chk_prompts_text_length");
    assertRejected(
        promptSql(library, "objekt", "Titel", "Text", "'{}'", SEEDED_ORGANIZATION_ID),
        "chk_prompts_variables_array");
    execute(
        promptSql(
            library, "liste", "Titel", "Text", "'[{\"name\": \"jahr\"}]'", SEEDED_ORGANIZATION_ID));
    assertRejected(
        promptSql(library, "ohne-titel", "  ", "Text", "'[]'", SEEDED_ORGANIZATION_ID),
        "chk_prompts_title_not_blank");
  }

  @Test
  void aPromptBelongsToALibraryOfItsOwnOrganization() throws SQLException {
    UUID library = insertPromptLibrary(insertUser());

    assertRejected(
        promptSql(library, "fremd", "Titel", "Text", "'[]'", insertOrganization().toString()),
        "fk_prompts_library_organization");
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private static String messageSql(
      UUID chat, int sequence, String role, String content, UUID promptId, String titleLiteral) {
    return "INSERT INTO chat_messages (id, chat_id, sequence, role, content, used_prompt_id,"
        + " used_prompt_title) VALUES (gen_random_uuid(), '"
        + chat
        + "', "
        + sequence
        + ", '"
        + role
        + "', '"
        + content
        + "', "
        + quoted(promptId)
        + ", "
        + (titleLiteral == null ? "NULL" : titleLiteral)
        + ")";
  }

  private static String noteSql(UUID chat, int position, String kind, String text) {
    return "INSERT INTO chat_note_items (id, chat_id, position, text, kind) VALUES"
        + " (gen_random_uuid(), '"
        + chat
        + "', "
        + position
        + ", '"
        + text
        + "', '"
        + kind
        + "')";
  }

  private static String markSql(UUID chat, UUID user, String pinnedAt, String archivedAt) {
    return "INSERT INTO chat_personal_marks (chat_id, user_id, pinned_at, archived_at) VALUES ('"
        + chat
        + "', '"
        + user
        + "', "
        + pinnedAt
        + ", "
        + archivedAt
        + ")";
  }

  private UUID insertPromptLibrary(UUID owner) throws SQLException {
    UUID id = insertAsset("PROMPT_LIBRARY", owner);
    execute(promptLibrarySql(id));
    return id;
  }

  private static String promptLibrarySql(UUID id) {
    return "INSERT INTO prompt_libraries (id, organization_id) VALUES ('"
        + id
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "')";
  }

  private static String promptSql(UUID library, String name, String text) {
    return promptSql(library, name, "Titel", text, "'[]'", SEEDED_ORGANIZATION_ID);
  }

  private static String promptSql(
      UUID library,
      String name,
      String title,
      String text,
      String variablesLiteral,
      String organization) {
    return "INSERT INTO prompts (id, library_id, organization_id, name, title, text, variables)"
        + " VALUES (gen_random_uuid(), '"
        + library
        + "', '"
        + organization
        + "', '"
        + name
        + "', '"
        + title
        + "', '"
        + text
        + "', "
        + variablesLiteral
        + "::jsonb)";
  }
}
