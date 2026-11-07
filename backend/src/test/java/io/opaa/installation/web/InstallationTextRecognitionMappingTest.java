package io.opaa.installation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.dto.InstallationAnswers;
import io.opaa.api.dto.InstallationFirstAdmin;
import io.opaa.api.dto.InstallationSignIn;
import io.opaa.api.dto.InstallationSignInMode;
import io.opaa.api.dto.InstallationStepRequest;
import io.opaa.api.dto.InstallationStepState;
import io.opaa.api.dto.InstallationTextRecognitionModel;
import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.common.FieldValidationException;
import io.opaa.installation.InstallationOverview;
import io.opaa.installation.InstallationStep;
import io.opaa.installation.StepInput;
import org.junit.jupiter.api.Test;

/**
 * The text recognition step on the wire (ADR-0060, Entscheidung 18): the request part becomes its
 * step input, an answer carries it along, and the response names the saved values, the key only as
 * set.
 */
class InstallationTextRecognitionMappingTest {

  @Test
  void theRequestPartBecomesTheStepInput() {
    InstallationTextRecognitionModel model =
        new InstallationTextRecognitionModel("http://vllm:8000/v1");
    model.setConnectionType(TextRecognitionConnectionType.OPENAI_VISION);
    model.setModel("paddle-vl");
    model.setApiKey("ocr-key");
    InstallationStepRequest request = new InstallationStepRequest();
    request.setTextRecognitionModel(model);

    assertThat(InstallationRequestMapper.toInput(InstallationStep.TEXT_RECOGNITION_MODEL, request))
        .isEqualTo(
            new StepInput.TextRecognitionModel(
                TextRecognitionConnectionType.OPENAI_VISION,
                "http://vllm:8000/v1",
                "paddle-vl",
                "ocr-key"));
    assertThatThrownBy(
            () ->
                InstallationRequestMapper.toInput(
                    InstallationStep.TEXT_RECOGNITION_MODEL, new InstallationStepRequest()))
        .isInstanceOf(FieldValidationException.class)
        .hasMessageContaining("textRecognitionModel");
  }

  @Test
  void anAnswerCarriesTheTextRecognitionModelAlong() {
    InstallationAnswers answers =
        new InstallationAnswers(
            new InstallationFirstAdmin("it@stadt.example", "IT", "Passwort-2026-lang"),
            new InstallationSignIn(InstallationSignInMode.LOCAL_ONLY));
    answers.setTextRecognitionModel(new InstallationTextRecognitionModel("http://opaa-ocr:5001"));

    assertThat(InstallationRequestMapper.toInputs(answers).getLast())
        .isEqualTo(new StepInput.TextRecognitionModel(null, "http://opaa-ocr:5001", null, null));
  }

  @Test
  void theResponseNamesTheSavedValuesAndOnlyWhetherAKeyIsSet() {
    InstallationStepState state =
        InstallationResponseMapper.toResponse(
            new InstallationOverview.StepState(
                InstallationStep.TEXT_RECOGNITION_MODEL,
                false,
                true,
                true,
                "geprüft",
                new InstallationOverview.TextRecognitionModel(
                    TextRecognitionConnectionType.DOCLING_SERVE,
                    "http://opaa-ocr:5001",
                    null,
                    true)));

    assertThat(state.getStep()).isEqualTo(io.opaa.api.dto.InstallationStep.TEXT_RECOGNITION_MODEL);
    assertThat(state.getTextRecognitionModel().getConnectionType())
        .isEqualTo(TextRecognitionConnectionType.DOCLING_SERVE);
    assertThat(state.getTextRecognitionModel().getBaseUrl()).isEqualTo("http://opaa-ocr:5001");
    assertThat(state.getTextRecognitionModel().getModel()).isNull();
    assertThat(state.getTextRecognitionModel().getApiKeySet()).isTrue();
  }
}
