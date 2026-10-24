package io.opaa.migration;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The import key of a chat is optional and unique per author and space only. */
class ChatImportKeyTest extends AbstractBaselineTest {

  @Test
  void aKeyIsUniquePerAuthorAndSpaceButFreeElsewhere() throws Exception {
    UUID author = insertUser();
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
