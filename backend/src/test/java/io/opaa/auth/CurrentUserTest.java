package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.SystemRole;
import io.opaa.common.AccessDeniedException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrentUserTest {

  @Test
  void requireSystemAdminPassesForTheSystemAdministration() {
    CurrentUser admin =
        CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Admin");

    assertThatCode(() -> admin.requireSystemAdmin("Nur die Systemverwaltung"))
        .doesNotThrowAnyException();
  }

  @Test
  void requireSystemAdminRefusesEveryOtherRoleWithTheGivenMessage() {
    CurrentUser user =
        CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, "Nutzer");

    assertThatThrownBy(() -> user.requireSystemAdmin("Nur die Systemverwaltung darf das"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Nur die Systemverwaltung darf das");
  }
}
