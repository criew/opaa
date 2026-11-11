package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.ModelRole;
import io.opaa.api.types.ModelRoleCondition;
import io.opaa.llm.OptionalRoleState;
import io.opaa.llm.OptionalRoleStatus;
import org.junit.jupiter.api.Test;

/** The transcription line of the status page: state, endpoint, model and a German detail only. */
class TranscriptionRoleStatusDisplayTest {

  private static final String URL = "https://stt.example/v1";

  private static ModelRoleStatus shown(
      OptionalRoleState state, boolean timedOut, boolean rejected) {
    return SearchStatusService.transcriptionRole(
        new OptionalRoleStatus(state, URL, "whisper", "diagnostic", timedOut, rejected));
  }

  @Test
  void eachStateMapsToItsCondition() {
    assertThat(SearchStatusService.transcriptionRole(OptionalRoleStatus.disabled()))
        .satisfies(
            role -> {
              assertThat(role.role()).isEqualTo(ModelRole.TRANSCRIPTION);
              assertThat(role.condition()).isEqualTo(ModelRoleCondition.DISABLED);
              assertThat(role.detail()).contains("Voreinstellung");
            });
    assertThat(shown(OptionalRoleState.READY, false, false).condition())
        .isEqualTo(ModelRoleCondition.ACTIVE);
    assertThat(shown(OptionalRoleState.UNCONFIGURED, false, false).condition())
        .isEqualTo(ModelRoleCondition.UNCONFIGURED);
    assertThat(shown(OptionalRoleState.UNREACHABLE, false, false).condition())
        .isEqualTo(ModelRoleCondition.UNREACHABLE);
  }

  @Test
  void theSubCasesNameTheirCause() {
    assertThat(shown(OptionalRoleState.UNCONFIGURED, false, true).detail())
        .contains("Anmeldedaten", "Modelle → Spracheingabe");
    assertThat(shown(OptionalRoleState.UNCONFIGURED, false, false).detail())
        .contains("Modelle → Spracheingabe", "Basis-Adresse oder Modell");
    assertThat(shown(OptionalRoleState.UNREACHABLE, true, false).detail())
        .contains("OPAA_TRANSCRIPTION_TIMEOUT");
    assertThat(shown(OptionalRoleState.UNREACHABLE, false, false).detail())
        .contains("antwortet nicht");
  }

  /** The status shows the role, never how often or by whom it is used, nor the diagnostic. */
  @Test
  void theLineCarriesEndpointAndModelButNoUsageAndNoDiagnostic() {
    ModelRoleStatus ready = shown(OptionalRoleState.READY, false, false);

    assertThat(ready.endpoint()).isEqualTo(URL);
    assertThat(ready.modelIdentifier()).isEqualTo("whisper");
    assertThat(ready.detail()).doesNotContain("diagnostic").doesNotContainPattern("\\d");
  }
}
