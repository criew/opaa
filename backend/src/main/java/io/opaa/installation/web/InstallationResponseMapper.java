package io.opaa.installation.web;

import io.opaa.api.dto.InstallationBranding;
import io.opaa.api.dto.InstallationChatModelView;
import io.opaa.api.dto.InstallationEmbeddingModelView;
import io.opaa.api.dto.InstallationFirstAdminView;
import io.opaa.api.dto.InstallationMailView;
import io.opaa.api.dto.InstallationOidcProvider;
import io.opaa.api.dto.InstallationSignIn;
import io.opaa.api.dto.InstallationSignInMode;
import io.opaa.api.dto.InstallationStatusResponse;
import io.opaa.api.dto.InstallationStep;
import io.opaa.api.dto.InstallationStepState;
import io.opaa.installation.InstallationOverview;
import io.opaa.installation.StepInput;

/** The installation's domain records as API responses; secrets appear only as "set". */
final class InstallationResponseMapper {

  private InstallationResponseMapper() {}

  /**
   * {@code publicBaseUrlConfigured} is a property of the deployment ({@code OPAA_PUBLIC_BASE_URL}),
   * not of the installation, so the controller passes it in.
   */
  static InstallationStatusResponse toResponse(
      InstallationOverview overview, boolean publicBaseUrlConfigured) {
    return new InstallationStatusResponse(
        overview.pending(),
        overview.completable(),
        publicBaseUrlConfigured,
        overview.steps().stream().map(InstallationResponseMapper::toResponse).toList());
  }

  static InstallationStepState toResponse(InstallationOverview.StepState state) {
    InstallationStepState response =
        new InstallationStepState(
            InstallationStep.valueOf(state.step().name()),
            state.mandatory(),
            state.saved(),
            state.checked());
    response.setCheckMessage(state.checkMessage());
    switch (state.values()) {
      case null -> {}
      case InstallationOverview.FirstAdmin admin ->
          response.setFirstAdmin(
              new InstallationFirstAdminView(
                  admin.email(), admin.displayName(), admin.passwordSet()));
      case InstallationOverview.SignIn signIn -> response.setSignIn(toResponse(signIn));
      case InstallationOverview.ChatModel model -> {
        InstallationChatModelView view =
            new InstallationChatModelView(
                model.baseUrl(), model.model(), model.apiKeySet(), model.maxTokens());
        view.setTemperature(model.temperature());
        response.setChatModel(view);
      }
      case InstallationOverview.EmbeddingModel model -> {
        InstallationEmbeddingModelView view =
            new InstallationEmbeddingModelView(
                model.baseUrl(), model.model(), model.apiKeySet(), model.maxInputTokens());
        view.setDimensions(model.dimensions());
        response.setEmbeddingModel(view);
      }
      case InstallationOverview.Mail mail -> {
        InstallationMailView view =
            new InstallationMailView(
                mail.host(),
                mail.port(),
                mail.encryption(),
                mail.passwordSet(),
                mail.fromAddress());
        view.setUsername(mail.username());
        view.setFromName(mail.fromName());
        response.setMail(view);
      }
      case InstallationOverview.Branding branding -> {
        InstallationBranding view = new InstallationBranding();
        view.setProductName(branding.productName());
        view.setClaim(branding.claim());
        view.setAiNotice(branding.aiNotice());
        view.setPrimaryColor(branding.primaryColor());
        view.setDefaultColorScheme(branding.defaultColorScheme());
        response.setBranding(view);
      }
    }
    return response;
  }

  private static InstallationSignIn toResponse(InstallationOverview.SignIn signIn) {
    InstallationSignIn response =
        new InstallationSignIn(InstallationSignInMode.valueOf(signIn.mode().name()));
    StepInput.Oidc oidc = signIn.oidc();
    if (oidc != null) {
      InstallationOidcProvider provider =
          new InstallationOidcProvider(oidc.issuerUri(), oidc.clientId());
      provider.setJwkSetUri(oidc.jwkSetUri());
      provider.setDisplayName(oidc.displayName());
      response.setOidc(provider);
    }
    return response;
  }
}
