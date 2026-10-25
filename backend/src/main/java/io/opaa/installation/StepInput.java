package io.opaa.installation;

import java.math.BigDecimal;

/**
 * The values of one step as the installation API or the answer file hands them in, secrets in plain
 * text. They live only for the call: {@link InstallationService} stores the password as a hash and
 * the model key encrypted.
 */
public sealed interface StepInput {

  InstallationStep step();

  /**
   * The first system administration account; {@code requirePasswordChange} forces a new password at
   * its first sign-in, so the password handed in stops working once it is used.
   */
  record FirstAdmin(
      String email, String displayName, String password, boolean requirePasswordChange)
      implements StepInput {

    /** An account whose password the person chose in the assistant and keeps. */
    public FirstAdmin(String email, String displayName, String password) {
      this(email, displayName, password, false);
    }

    @Override
    public InstallationStep step() {
      return InstallationStep.FIRST_ADMIN;
    }
  }

  /** The sign-in method; {@code oidc} is read only for {@link SignInMode#LOCAL_AND_OIDC}. */
  record SignIn(SignInMode mode, Oidc oidc) implements StepInput {
    @Override
    public InstallationStep step() {
      return InstallationStep.SIGN_IN;
    }
  }

  /** The OIDC identity provider of {@link SignInMode#LOCAL_AND_OIDC}. */
  record Oidc(String issuerUri, String clientId, String jwkSetUri, String displayName) {}

  /**
   * The chat model. {@code apiKey}: {@code null} keeps a key saved before, an empty value removes
   * it; {@code maxTokens} {@code null} means the default.
   */
  record ChatModel(
      String baseUrl, String model, String apiKey, BigDecimal temperature, Integer maxTokens)
      implements StepInput {
    @Override
    public InstallationStep step() {
      return InstallationStep.CHAT_MODEL;
    }
  }

  /** How people sign in after the installation. */
  enum SignInMode {
    LOCAL_ONLY,
    LOCAL_AND_OIDC
  }
}
