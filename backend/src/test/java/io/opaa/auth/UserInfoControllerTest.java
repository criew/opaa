package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.UserInfoResponse;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.local.LocalAccountSelfDisclosure;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserInfoControllerTest {

  @Test
  void meAnswersFromTheCallerSnapshotAndTheCreationReason() {
    UUID id = UUID.randomUUID();
    CurrentUser caller =
        CurrentUser.of(id, UUID.randomUUID(), SystemRole.AUDITOR, "Admin", "admin@opaa.local");
    LocalAccountSelfDisclosure disclosure = mock(LocalAccountSelfDisclosure.class);
    when(disclosure.createdReasonOf(id)).thenReturn(Optional.of("Notanker-Konto"));

    UserInfoResponse response = new UserInfoController(disclosure).me(caller);

    assertThat(response.getId()).isEqualTo(id);
    assertThat(response.getEmail()).isEqualTo("admin@opaa.local");
    assertThat(response.getDisplayName()).isEqualTo("Admin");
    assertThat(response.getSystemRole()).isEqualTo("AUDITOR");
    assertThat(response.getCreatedReason()).isEqualTo("Notanker-Konto");
  }

  // An account of an identity provider has no local_credentials row at all; "absent" must read as
  // "no creation reason", never as an error.
  @Test
  void leavesTheCreationReasonOutForAnAccountOfAnIdentityProvider() {
    CurrentUser caller =
        CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, "Erika", "e@x.test");
    LocalAccountSelfDisclosure disclosure = mock(LocalAccountSelfDisclosure.class);
    when(disclosure.createdReasonOf(caller.id())).thenReturn(Optional.empty());

    UserInfoResponse response = new UserInfoController(disclosure).me(caller);

    assertThat(response.getCreatedReason()).isNull();
  }
}
