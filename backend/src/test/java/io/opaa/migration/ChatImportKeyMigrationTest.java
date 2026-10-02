package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code assistant/2026-10-02-chat-import-key.yaml} on an installation that already has chats: they
 * keep no key, and a key is unique per author and space only.
 */
class ChatImportKeyMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/assistant/2026-10-02-chat-import-key.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  private UUID author;
  private UUID existingChat;

  @BeforeEach
  void applyOnAnInstallationWithChats() throws Exception {
    author = insertUser();
    existingChat = insertChat(author);
    applyChangelog(connection, FILE);
  }

  @Test
  void anExistingChatHasNoImportKey() throws Exception {
    assertThat(stringOf("SELECT import_key FROM chats WHERE id = '" + existingChat + "'"))
        .isNull();
  }

  @Test
  void aKeyIsUniquePerAuthorAndSpaceButFreeElsewhere() throws Exception {
    UUID space = insertSpace(author);
    UUID otherAuthor = insertUser();
    execute(chat(space, author, "gebuehren"));
    execute(chat(space, author, null));
    execute(chat(space, author, null));
    execute(chat(space, otherAuthor, "gebuehren"));
    execute(chat(insertSpace(author), author, "gebuehren"));

    assertRejected(chat(space, author, "gebuehren"), "uk_chats_import_key");
  }

  private static String chat(UUID space, UUID author, String importKey) {
    return "INSERT INTO chats (id, space_id, author_id, organization_id, import_key) VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + space
        + "', '"
        + author
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', "
        + quoted(importKey)
        + ")";
  }
}
