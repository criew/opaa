package io.opaa.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.UserInfoResponse;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The field-by-field assurance AGENTS.md asks of every hand-written mapper: the controller test
 * below works on derivations of the caller snapshot, so without this nothing would notice a field
 * the mapper stops filling.
 */
class UserInfoResponseMapperTest {

  @Test
  void fillsEveryFieldFromTheCallerTheCreationReasonAndTheQuickTourHint() {
    UUID id = UUID.randomUUID();
    CurrentUser caller =
        CurrentUser.of(id, UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Erika Muster", "e@x.test");

    UserInfoResponse response =
        UserInfoResponseMapper.toResponse(caller, "Projektbefristung", true);

    assertThat(response.getId()).isEqualTo(id);
    assertThat(response.getEmail()).isEqualTo("e@x.test");
    assertThat(response.getDisplayName()).isEqualTo("Erika Muster");
    assertThat(response.getSystemRole()).isEqualTo("SYSTEM_ADMIN");
    assertThat(response.getCreatedReason()).isEqualTo("Projektbefristung");
    assertThat(response.getQuickTourHintDone()).isTrue();
  }

  @Test
  void leavesTheCreationReasonNullForAnAccountThatHasNone() {
    CurrentUser caller =
        CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, "Max", null);

    UserInfoResponse response = UserInfoResponseMapper.toResponse(caller, null, false);

    assertThat(response.getCreatedReason()).isNull();
    assertThat(response.getEmail()).isNull();
    assertThat(response.getQuickTourHintDone()).isFalse();
  }
}
