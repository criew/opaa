package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.SystemRole;
import io.opaa.common.AccessDeniedException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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

  @Test
  void anAccessTokenCallerCarriesItsTokenAndNeverTheSystemRole() {
    User person = new User("s", "i", null, "Person");
    person.setSystemRole(SystemRole.SYSTEM_ADMIN);
    UUID tokenId = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest();

    CurrentUserArgumentResolver.bindExternalAccessCaller(request, person, tokenId);

    CurrentUser caller = CurrentUserArgumentResolver.callerOf(request);
    assertThat(caller.accessTokenId()).isEqualTo(tokenId);
    assertThat(caller.systemRole()).isEqualTo(SystemRole.USER);
  }

  @Test
  void anAccessTokenCallerWithoutItsTokenIsNeverBound() {
    MockHttpServletRequest request = new MockHttpServletRequest();

    assertThatThrownBy(
            () ->
                CurrentUserArgumentResolver.bindExternalAccessCaller(
                    request, new User("s", "i", null, "Person"), null))
        .isInstanceOf(NullPointerException.class);
    assertThat(CurrentUserArgumentResolver.callerOf(request)).isNull();
  }

  @Test
  void aSignedInPersonCarriesNoAccessToken() {
    CurrentUser person =
        CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, "Nutzer");

    assertThat(person.accessTokenId()).isNull();
  }
}
