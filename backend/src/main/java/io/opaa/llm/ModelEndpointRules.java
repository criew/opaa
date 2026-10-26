package io.opaa.llm;

import io.opaa.common.FieldValidationException.FieldError;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * The rules every entered model endpoint follows, in the installation as in the model
 * administration: lengths, an http(s) address without credentials, a key without control
 * characters, and that a stored key stays only with the same endpoint; the address check is {@link
 * ModelAddressPolicy}. Field errors are named {@code <prefix>.baseUrl}, {@code <prefix>.model} and
 * {@code <prefix>.apiKey}.
 */
public final class ModelEndpointRules {

  public static final int MAX_ADDRESS = 500;
  public static final int MAX_NAME = 200;
  public static final int MAX_API_KEY = 2000;

  public static final String INVALID = "INVALID";
  public static final String REQUIRED = "REQUIRED";
  public static final String TOO_LONG = "TOO_LONG";
  public static final String BLOCKED = "BLOCKED";

  private ModelEndpointRules() {}

  /**
   * Adds the violated rules of address, model and key to {@code errors}; with {@code required}
   * false, an empty address and model are allowed, a given address is still checked.
   */
  public static void endpointErrors(
      String prefix,
      String baseUrl,
      String model,
      String apiKey,
      boolean required,
      List<FieldError> errors) {
    if (baseUrl.isEmpty()) {
      if (required) {
        errors.add(new FieldError(prefix + ".baseUrl", REQUIRED, "Die Basis-Adresse fehlt."));
      }
    } else if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
      errors.add(
          new FieldError(
              prefix + ".baseUrl",
              INVALID,
              "Die Basis-Adresse muss mit http:// oder https:// beginnen."));
    } else if (ModelEndpointUri.containsCredentials(baseUrl)) {
      errors.add(
          new FieldError(
              prefix + ".baseUrl",
              INVALID,
              "Die Basis-Adresse darf keine Anmeldedaten enthalten; der Schlüssel gehört in das"
                  + " Feld Zugangsschlüssel."));
    }
    if (model.isEmpty() && required) {
      errors.add(new FieldError(prefix + ".model", REQUIRED, "Die Modell-Kennung fehlt."));
    }
    requireAtMost(errors, prefix + ".baseUrl", baseUrl, MAX_ADDRESS);
    requireAtMost(errors, prefix + ".model", model, MAX_NAME);
    requireAtMost(errors, prefix + ".apiKey", apiKey, MAX_API_KEY);
    if (apiKey != null && apiKey.strip().chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      errors.add(
          new FieldError(
              prefix + ".apiKey",
              INVALID,
              "Der Zugangsschlüssel enthält Steuerzeichen wie einen Zeilenumbruch."));
    }
  }

  /** The lengths of the specification ({@code maxLength}), enforced for every way in. */
  public static void requireAtMost(List<FieldError> errors, String field, String value, int max) {
    if (value != null && value.length() > max) {
      errors.add(
          new FieldError(
              field, TOO_LONG, "Die Angabe ist zu lang (höchstens " + max + " Zeichen)."));
    }
  }

  /**
   * Whether a saved key may stay with {@code next}: scheme, host, port and path unchanged, so a key
   * never follows the address to another endpoint.
   */
  public static boolean sameEndpoint(String previous, String next) {
    try {
      return endpointKey(previous).equals(endpointKey(next));
    } catch (URISyntaxException | NullPointerException e) {
      return false;
    }
  }

  private static String endpointKey(String baseUrl) throws URISyntaxException {
    URI uri = new URI(baseUrl.strip());
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
    String path = uri.getPath() == null ? "" : uri.getPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port + path;
  }
}
