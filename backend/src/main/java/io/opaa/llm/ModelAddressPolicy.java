package io.opaa.llm;

import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * The address check of every model endpoint the installation or the model administration stores,
 * tests or lists (ADR-0060, Entscheidungen 2 and 10). Its {@link TargetAddressValidator} is built
 * from {@code opaa.model.target-validation} alone - not from the sources' check, so a model host
 * never becomes a permitted target of a connector and a source host never a permitted model. Calls
 * at run time are not checked: a stored endpoint keeps working when the allowlist narrows.
 */
public class ModelAddressPolicy {

  public static final String ALLOWLIST_HINT =
      "Interne Adressen von Modellen gibt der Betrieb über OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST"
          + " frei.";

  private final TargetAddressValidator validator;

  public ModelAddressPolicy(TargetAddressValidator validator) {
    this.validator = validator;
  }

  /** The production policy: switch and allowlist of {@code opaa.model.target-validation}. */
  public static ModelAddressPolicy of(ModelTargetValidationProperties properties) {
    return new ModelAddressPolicy(
        new TargetAddressValidator(properties.enabled(), properties.allowlist()));
  }

  /** A strict policy admitting only {@code allowlist}, for tests. */
  public static ModelAddressPolicy allowing(List<String> allowlist) {
    return new ModelAddressPolicy(new TargetAddressValidator(true, allowlist));
  }

  /** A policy with checking turned off, for tests of behaviour unrelated to the check. */
  public static ModelAddressPolicy disabled() {
    return new ModelAddressPolicy(TargetAddressValidator.disabled());
  }

  /**
   * Why the check refuses {@code baseUrl}, or {@code null}; the German rejection names the setting
   * that releases an internal host ({@link #ALLOWLIST_HINT}).
   */
  public String blockedReason(String baseUrl) {
    try {
      validator.validate(new URI(baseUrl.strip()));
      return null;
    } catch (URISyntaxException e) {
      return "Die Basis-Adresse ist keine gültige URL.";
    } catch (IOException e) {
      return e.getMessage() + " " + ALLOWLIST_HINT;
    }
  }

  /**
   * {@link #blockedReason(String)} for a change of a stored endpoint: an address naming the same
   * endpoint as {@code storedBaseUrl} ({@link ModelEndpointRules#sameEndpoint}) passes unchecked,
   * so a model stored before the allowlist narrowed stays editable and testable as it is.
   */
  public String blockedReason(String baseUrl, String storedBaseUrl) {
    if (storedBaseUrl != null && ModelEndpointRules.sameEndpoint(storedBaseUrl, baseUrl)) {
      return null;
    }
    return blockedReason(baseUrl);
  }

  /** Whether an allowlist entry names the host and port of {@code baseUrl}, without resolving. */
  public boolean isAllowlisted(String baseUrl) {
    try {
      return validator.isAllowlisted(new URI(baseUrl.strip()));
    } catch (URISyntaxException e) {
      return false;
    }
  }
}
