package io.opaa.llm;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The transcription model role as the administration stored it (ADR-0060, Entscheidung 10), built
 * like {@link RerankEndpoint}, with the language passed to the model: an ISO-639-1 code, empty to
 * let the model detect it. {@link #toString()} never names the key or userinfo of the address.
 */
public record TranscriptionEndpoint(
    boolean enabled, String baseUrl, String model, String apiKey, String language) {

  /** The language of the factory default. */
  public static final String DEFAULT_LANGUAGE = "de";

  /** The factory default: off, unbound, German. */
  public static final TranscriptionEndpoint FACTORY_DEFAULT =
      new TranscriptionEndpoint(false, "", "", "", DEFAULT_LANGUAGE);

  /** The message of a language {@link #isValidLanguage} refuses. */
  public static final String LANGUAGE_RULE =
      "Die Sprache ist ein Kürzel aus zwei Buchstaben nach ISO 639-1, etwa de oder en;"
          + " leer erkennt das Modell die Sprache selbst.";

  private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z]{2}");

  /** A language as stored: stripped and lower-case; {@code null} stays {@code null}. */
  public static String normalisedLanguage(String language) {
    return language == null ? null : language.strip().toLowerCase(Locale.ROOT);
  }

  /** Whether a {@link #normalisedLanguage} is stored as given: empty or two letters. */
  public static boolean isValidLanguage(String normalised) {
    return normalised == null
        || normalised.isEmpty()
        || LANGUAGE_CODE.matcher(normalised).matches();
  }

  public TranscriptionEndpoint {
    baseUrl = baseUrl == null ? "" : baseUrl.strip();
    model = model == null ? "" : model.strip();
    apiKey = apiKey == null ? "" : apiKey.strip();
    language = language == null ? "" : language.strip();
  }

  /** Whether both endpoint fields are present. */
  public boolean bound() {
    return !baseUrl.isEmpty() && !model.isEmpty();
  }

  /** Endpoint and model as far as they may be logged: never the key, never userinfo. */
  public String describeWithoutSecrets() {
    if (!bound()) {
      return "(no endpoint configured)";
    }
    return model + " @ " + safeBaseUrl();
  }

  String safeBaseUrl() {
    return ModelEndpointUri.containsCredentials(baseUrl)
        ? "(base URL with credentials - rejected)"
        : baseUrl;
  }

  @Override
  public String toString() {
    return "TranscriptionEndpoint[enabled="
        + enabled
        + ", baseUrl="
        + safeBaseUrl()
        + ", model="
        + model
        + ", apiKey=***, language="
        + language
        + "]";
  }
}
