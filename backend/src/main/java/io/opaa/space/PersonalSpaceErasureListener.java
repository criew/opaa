package io.opaa.space;

import io.opaa.account.AccountErasureEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Deletes the personal space of an erased account once its chats are gone. */
@Component
class PersonalSpaceErasureListener {

  private final SpaceService spaces;

  PersonalSpaceErasureListener(SpaceService spaces) {
    this.spaces = spaces;
  }

  @Order(AccountErasureEvent.PERSONAL_SPACES_ORDER)
  @EventListener
  void onAccountErasure(AccountErasureEvent event) {
    spaces.deletePersonalSpaceOfErasedAccount(event.user().getId(), event.actor().id());
  }
}
