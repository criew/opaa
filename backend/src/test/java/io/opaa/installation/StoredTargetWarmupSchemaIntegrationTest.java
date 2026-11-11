package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The start-up check of stored targets reads the base URL of every model role from the real schema
 * (ADR-0063, Entscheidung 4): chat models, the embedding model and the singleton rows of rerank,
 * transcription and text recognition. The singleton rows are restored by {@code SeededRowRestorer};
 * this class removes only the rows carrying its own addresses.
 */
@OpaaIntegrationTest
class StoredTargetWarmupSchemaIntegrationTest {

  private static final String CHAT = "https://chat.warmup-schema.example/v1";
  private static final String RERANK = "https://rerank.warmup-schema.example/v1";
  private static final String TRANSCRIPTION = "https://transcription.warmup-schema.example/v1";
  private static final String TEXT_RECOGNITION = "https://ocr.warmup-schema.example";
  private static final List<String> SINGLETON_TABLES =
      List.of("rerank_model", "transcription_model", "text_recognition_model");

  private final UUID chatModelId = UUID.randomUUID();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private StoredTargetWarmup warmup;

  @BeforeEach
  void setUp() {
    removeOwnRows();
  }

  @AfterEach
  void tearDown() {
    removeOwnRows();
  }

  @Test
  void theEndpointsOfEveryModelRoleAreReadFromTheSchema() {
    jdbc.update(
        "INSERT INTO llm_models (id, display_name, base_url, model_identifier)"
            + " VALUES (?, 'Warmup schema', ?, 'chat-model')",
        chatModelId,
        CHAT);
    jdbc.update(
        "INSERT INTO rerank_model (id, enabled, base_url, updated_at) VALUES (1, true, ?, now())"
            + " ON CONFLICT (id) DO UPDATE SET base_url = EXCLUDED.base_url",
        RERANK);
    jdbc.update(
        "INSERT INTO transcription_model (id, enabled, base_url, updated_at)"
            + " VALUES (1, true, ?, now())"
            + " ON CONFLICT (id) DO UPDATE SET base_url = EXCLUDED.base_url",
        TRANSCRIPTION);
    jdbc.update(
        "INSERT INTO text_recognition_model (id, enabled, connection_type, base_url, updated_at)"
            + " VALUES (1, true, 'DOCLING_SERVE', ?, now())"
            + " ON CONFLICT (id) DO UPDATE SET base_url = EXCLUDED.base_url",
        TEXT_RECOGNITION);
    List<String> embedding =
        jdbc.queryForList("SELECT base_url FROM embedding_model", String.class);

    List<String> endpoints = warmup.storedModelEndpoints();

    assertThat(embedding).as("the embedding model the test installation sets up").isNotEmpty();
    assertThat(endpoints)
        .contains(CHAT, RERANK, TRANSCRIPTION, TEXT_RECOGNITION)
        .containsAll(embedding)
        .doesNotHaveDuplicates();
  }

  private void removeOwnRows() {
    jdbc.update("DELETE FROM llm_models WHERE id = ?", chatModelId);
    for (String table : SINGLETON_TABLES) {
      jdbc.update(
          "DELETE FROM " + table + " WHERE base_url IN (?, ?, ?)",
          RERANK,
          TRANSCRIPTION,
          TEXT_RECOGNITION);
    }
  }
}
