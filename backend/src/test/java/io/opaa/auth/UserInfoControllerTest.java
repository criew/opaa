package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.UserInfoResponse;
import io.opaa.api.types.SystemRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserInfoControllerTest {

  @Test
  void meAnswersFromTheCallerSnapshotAlone() {
    UUID id = UUID.randomUUID();
    CurrentUser caller =
        CurrentUser.of(id, UUID.randomUUID(), SystemRole.AUDITOR, "Admin", "admin@opaa.local");

    UserInfoResponse response = new UserInfoController().me(caller);

    assertThat(response.getId()).isEqualTo(id);
    assertThat(response.getEmail()).isEqualTo("admin@opaa.local");
    assertThat(response.getDisplayName()).isEqualTo("Admin");
    assertThat(response.getSystemRole()).isEqualTo("AUDITOR");
  }
}
