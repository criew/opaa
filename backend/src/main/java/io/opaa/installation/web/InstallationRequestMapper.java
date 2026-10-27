package io.opaa.installation.web;

import io.opaa.api.dto.InstallationAnswers;
import io.opaa.api.dto.InstallationChatModel;
import io.opaa.api.dto.InstallationEmbeddingModel;
import io.opaa.api.dto.InstallationFirstAdmin;
import io.opaa.api.dto.InstallationMail;
import io.opaa.api.dto.InstallationOidcProvider;
import io.opaa.api.dto.InstallationSignIn;
import io.opaa.api.dto.InstallationStepRequest;
import io.opaa.common.FieldValidationException;
import io.opaa.installation.InstallationStep;
import io.opaa.installation.StepInput;
import java.util.ArrayList;
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
      case EMBEDDING_MODEL -> embeddingModel(request.getEmbeddingModel());
      case MAIL -> mail(request.getMail());
    };
  }

  static List<StepInput> toInputs(InstallationAnswers body) {
    if (body == null) {
      throw missing("firstAdmin");
    }
    List<StepInput> inputs =
        new ArrayList<>(
            List.of(
                firstAdmin(body.getFirstAdmin()),
                signIn(body.getSignIn()),
                chatModel(body.getChatModel()),
                embeddingModel(body.getEmbeddingModel())));
    if (body.getMail() != null) {
      InstallationMail mail = body.getMail();
      // An answer names its password or none; there is no saved one to keep.
      inputs.add(mailInput(mail, mail.getPassword() == null ? "" : mail.getPassword()));
    }
    return List.copyOf(inputs);
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

  private static StepInput embeddingModel(InstallationEmbeddingModel model) {
    if (model == null) {
      throw missing("embeddingModel");
    }
    return new StepInput.EmbeddingModel(
        model.getBaseUrl(), model.getModel(), model.getApiKey(), model.getMaxInputTokens());
  }

  private static StepInput mail(InstallationMail mail) {
    if (mail == null) {
      throw missing("mail");
    }
    return mailInput(mail, mail.getPassword());
  }

  private static StepInput mailInput(InstallationMail mail, String password) {
    return new StepInput.Mail(
        mail.getHost(),
        mail.getPort(),
        mail.getEncryption(),
        mail.getUsername(),
        password,
        mail.getFromAddress(),
        mail.getFromName());
  }

  private static FieldValidationException missing(String field) {
    return FieldValidationException.of(field, REQUIRED, "Die Angaben " + field + " fehlen.");
  }
}
