package io.opaa.llm.transcription;

import io.opaa.account.AccountErasureEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Deletes the transcription vocabulary of an erased account, in the erasure's transaction. */
@Component
class TranscriptionVocabularyAccountErasureListener {

  private final JdbcTemplate jdbc;

  TranscriptionVocabularyAccountErasureListener(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Order(AccountErasureEvent.PERSONAL_DATA_ORDER)
  @EventListener
  void onAccountErasure(AccountErasureEvent event) {
    jdbc.update("DELETE FROM transcription_vocabularies WHERE user_id = ?", event.user().getId());
  }
}
