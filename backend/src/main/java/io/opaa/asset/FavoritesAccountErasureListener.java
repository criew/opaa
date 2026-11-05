package io.opaa.asset;

import io.opaa.account.AccountErasureEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Deletes the favourites of an erased account, in the erasure's transaction. */
@Component
class FavoritesAccountErasureListener {

  private final JdbcTemplate jdbc;

  FavoritesAccountErasureListener(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Order(AccountErasureEvent.PERSONAL_DATA_ORDER)
  @EventListener
  void onAccountErasure(AccountErasureEvent event) {
    jdbc.update("DELETE FROM asset_favorites WHERE user_id = ?", event.user().getId());
  }
}
