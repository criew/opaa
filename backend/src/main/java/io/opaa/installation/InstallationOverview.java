package io.opaa.installation;

import io.opaa.api.types.ColorScheme;
import io.opaa.api.types.MailEncryption;
import java.math.BigDecimal;
import java.util.List;

/**
 * What the installation API shows of a pending installation: every step with its saved values,
 * never a secret, only whether one is set.
 */
public record InstallationOverview(boolean pending, boolean completable, List<StepState> steps) {

  public InstallationOverview {
    steps = List.copyOf(steps);
  }

  /** One step; {@code values} is {@code null} while the step is not saved. */
  public record StepState(
      InstallationStep step,
      boolean mandatory,
      boolean saved,
      boolean checked,
      String checkMessage,
      SavedValues values) {}

  /** The saved values of a step without its secret. */
  public sealed interface SavedValues {}

  public record FirstAdmin(String email, String displayName, boolean passwordSet)
      implements SavedValues {}

  public record SignIn(StepInput.SignInMode mode, StepInput.Oidc oidc) implements SavedValues {}

  public record ChatModel(
      String baseUrl, String model, boolean apiKeySet, BigDecimal temperature, int maxTokens)
      implements SavedValues {}

  /** The mail server; {@code passwordSet} says whether a password is saved, never which. */
  public record Mail(
      String host,
      int port,
      MailEncryption encryption,
      String username,
      boolean passwordSet,
      String fromAddress,
      String fromName)
      implements SavedValues {}

  /** The branding as saved; {@code null} in a field means the OPAA default. */
  public record Branding(
      String productName,
      String claim,
      String aiNotice,
      String primaryColor,
      ColorScheme defaultColorScheme)
      implements SavedValues {}

  /** {@code dimensions} is the vector size of the last successful probe embedding, else null. */
  public record EmbeddingModel(
      String baseUrl, String model, boolean apiKeySet, int maxInputTokens, Integer dimensions)
      implements SavedValues {}
}
