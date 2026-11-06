package io.opaa.llm;

import io.opaa.api.types.TextRecognitionConnectionType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The text recognition model role as the administration stored it, built like {@link
 * TranscriptionEndpoint}. {@code model} is empty for {@link
 * TextRecognitionConnectionType#DOCLING_SERVE}, which takes no model. {@link #toString()} never
 * names the key or userinfo of the address.
 */
public record TextRecognitionEndpoint(
    boolean enabled,
    TextRecognitionConnectionType connectionType,
    String baseUrl,
    String model,
    String apiKey) {

  /** The connection type of the factory default: the shipped OCR container speaks docling-serve. */
  public static final TextRecognitionConnectionType DEFAULT_CONNECTION_TYPE =
      TextRecognitionConnectionType.DOCLING_SERVE;

  /** The factory default: off, docling-serve, unbound. */
  public static final TextRecognitionEndpoint FACTORY_DEFAULT =
      new TextRecognitionEndpoint(false, DEFAULT_CONNECTION_TYPE, "", "", "");

  public TextRecognitionEndpoint {
    connectionType = connectionType == null ? DEFAULT_CONNECTION_TYPE : connectionType;
    baseUrl = baseUrl == null ? "" : baseUrl.strip();
    model =
        model == null || connectionType == TextRecognitionConnectionType.DOCLING_SERVE
            ? ""
            : model.strip();
    apiKey = apiKey == null ? "" : apiKey.strip();
  }

  /** Whether the connection type needs a model identifier. */
  public static boolean needsModel(TextRecognitionConnectionType connectionType) {
    return connectionType == TextRecognitionConnectionType.OPENAI_VISION;
  }

  /** Whether address and, for a vision model, the model are present. */
  public boolean bound() {
    return !baseUrl.isEmpty() && (!needsModel(connectionType) || !model.isEmpty());
  }

  /**
   * Names connection type, address and model, never the key: changes exactly when another service
   * or model would read the pages. Indexing records it as the recipient of recognised text.
   */
  public String configurationId() {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (connectionType.name() + "\n" + baseUrl + "\n" + model)
                      .getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every JRE", e);
    }
  }

  /** The model as the shared role observation keys it: connection type and model together. */
  String observedModel() {
    return connectionType.name() + ":" + model;
  }

  /** Endpoint and model as far as they may be logged: never the key, never userinfo. */
  public String describeWithoutSecrets() {
    if (!bound()) {
      return "(no endpoint configured)";
    }
    String address =
        ModelEndpointUri.containsCredentials(baseUrl)
            ? "(base URL with credentials - rejected)"
            : baseUrl;
    return connectionType + (model.isEmpty() ? "" : " " + model) + " @ " + address;
  }

  @Override
  public String toString() {
    return "TextRecognitionEndpoint[enabled="
        + enabled
        + ", "
        + describeWithoutSecrets()
        + ", apiKey=***]";
  }
}
