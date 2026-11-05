package io.opaa.connection.account;

import io.opaa.account.AccountErasureEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Removes what names an erased account at the providers of its connections, in the erasure's
 * transaction: the label of each connected account, label and person of the library connections it
 * consented to, and the authorizations still open. The rows of
 * the connected accounts stay for the lifecycle, which ends a remaining one and deletes its secret
 * as for any deactivated account.
 */
@Component
class ConnectedAccountErasureListener {

  private final JdbcTemplate jdbc;

  ConnectedAccountErasureListener(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Order(AccountErasureEvent.PERSONAL_DATA_ORDER)
  @EventListener
  void onAccountErasure(AccountErasureEvent event) {
    jdbc.update(
        "UPDATE connected_accounts SET account_label_ciphertext = NULL WHERE user_id = ?",
        event.user().getId());
    jdbc.update(
        "UPDATE library_connections SET account_label = NULL, connected_by = NULL"
            + " WHERE connected_by = ?",
        event.user().getId());
    jdbc.update("DELETE FROM connection_authorizations WHERE user_id = ?", event.user().getId());
  }
}
