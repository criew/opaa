package io.opaa.library;

import io.opaa.account.AccountErasureEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Marks the private libraries of an erased account for the erasure of the deletion run. */
@Component
class PrivateLibraryAccountErasureListener {

  private final PrivateLibraryDeletionRun run;

  PrivateLibraryAccountErasureListener(PrivateLibraryDeletionRun run) {
    this.run = run;
  }

  @Order(AccountErasureEvent.PERSONAL_DATA_ORDER)
  @EventListener
  void onAccountErasure(AccountErasureEvent event) {
    run.markOfErasedAccount(event.user().getId(), event.at());
  }
}
