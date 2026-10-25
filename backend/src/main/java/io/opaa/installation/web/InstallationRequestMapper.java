package io.opaa.installation.web;

import io.opaa.api.dto.InstallationAnswers;
import io.opaa.api.dto.InstallationChatModel;
import io.opaa.api.dto.InstallationFirstAdmin;
import io.opaa.api.dto.InstallationOidcProvider;
import io.opaa.api.dto.InstallationSignIn;
import io.opaa.api.dto.InstallationStepRequest;
import io.opaa.common.FieldValidationException;
import io.opaa.installation.InstallationStep;
import io.opaa.installation.StepInput;
import java.util.List;

/** The requests of the installation API as {@link StepInput}s; a missing part is a field error. */
final class InstallationRequestMapper {

  private static final String REQUIRED = "REQUIRED";

  private InstallationRequestMapper() {}

  static StepInput toInput(InstallationStep step, InstallationStepRequest body) {
    InstallationStepRequest request = body == null ? new InstallationStepRequest() : body;
    return switch (step) {
      case FIRST_ADMIN -> firstAdmin(request.getFirstAdmin());
      case SIGN_IN -> signIn(request.getSignIn());
      case CHAT_MODEL -> chatModel(request.getChatModel());
    };
  }

  static List<StepInput> toInputs(InstallationAnswers body) {
    if (body == null) {
      throw missing("firstAdmin");
    }
    return List.of(
        firstAdmin(body.getFirstAdmin()), signIn(body.getSignIn()), chatModel(body.getChatModel()));
  }

  private static StepInput firstAdmin(InstallationFirstAdmin admin) {
    if (admin == null) {
      throw missing("firstAdmin");
    }
    return new StepInput.FirstAdmin(admin.getEmail(), admin.getDisplayName(), admin.getPassword());
  }

  private static StepInput signIn(InstallationSignIn signIn) {
    if (signIn == null) {
      throw missing("signIn");
    }
    StepInput.SignInMode mode =
        signIn.getMode() == null ? null : StepInput.SignInMode.valueOf(signIn.getMode().name());
    InstallationOidcProvider oidc = signIn.getOidc();
    return new StepInput.SignIn(
        mode,
        oidc == null
            ? null
            : new StepInput.Oidc(
                oidc.getIssuerUri(),
                oidc.getClientId(),
                oidc.getJwkSetUri(),
                oidc.getDisplayName()));
  }

  private static StepInput chatModel(InstallationChatModel model) {
    if (model == null) {
      throw missing("chatModel");
    }
    return new StepInput.ChatModel(
        model.getBaseUrl(),
        model.getModel(),
        model.getApiKey(),
        model.getTemperature(),
        model.getMaxTokens());
  }

  private static FieldValidationException missing(String field) {
    return FieldValidationException.of(field, REQUIRED, "Die Angaben " + field + " fehlen.");
  }
}
