package io.opaa.indexing.source;

import io.opaa.common.ValidationException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A service account key file as the core reads it (ADR-0040, Entscheidung 2): only {@code
 * client_email}, {@code private_key_id} and {@code private_key}. No other field - {@code token_uri}
 * included - decides a target. Never handed to a connector; {@link #toString()} names the account
 * only.
 */
public final class ServiceAccountKey {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String INVALID =
      "sourceCredentials: Der Dienstkonto-Schlüssel ist keine gültige JSON-Schlüsseldatei eines"
          + " Dienstkontos";

  private final String clientEmail;
  private final String privateKeyId;
  private final String privateKeyPem;
  private final RSAPrivateKey privateKey;

  private ServiceAccountKey(
      String clientEmail, String privateKeyId, String privateKeyPem, RSAPrivateKey privateKey) {
    this.clientEmail = clientEmail;
    this.privateKeyId = privateKeyId;
    this.privateKeyPem = privateKeyPem;
    this.privateKey = privateKey;
  }

  /**
   * Reads a key file, uploaded or stored.
   *
   * @throws ValidationException (German 400) for anything but a JSON key file with an RSA key; the
   *     message never quotes the input
   */
  public static ServiceAccountKey parse(String json) {
    JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (JacksonException e) {
      throw new ValidationException(INVALID);
    }
    if (root == null || !root.isObject()) {
      throw new ValidationException(INVALID);
    }
    String clientEmail = text(root, "client_email");
    String privateKeyId = text(root, "private_key_id");
    String privateKeyPem = text(root, "private_key");
    if (clientEmail == null || privateKeyId == null || privateKeyPem == null) {
      throw new ValidationException(
          INVALID + " (erwartet client_email, private_key_id und private_key)");
    }
    if (clientEmail.length() > 320 || !clientEmail.contains("@")) {
      throw new ValidationException(INVALID + " (client_email ist keine Adresse)");
    }
    return new ServiceAccountKey(
        clientEmail, privateKeyId, privateKeyPem, readPrivateKey(privateKeyPem));
  }

  /** The stored form: the three fields the core reads, nothing else of the file. */
  public String storedForm() {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("client_email", clientEmail);
    fields.put("private_key_id", privateKeyId);
    fields.put("private_key", privateKeyPem);
    return JSON.writeValueAsString(fields);
  }

  public String clientEmail() {
    return clientEmail;
  }

  String privateKeyId() {
    return privateKeyId;
  }

  RSAPrivateKey privateKey() {
    return privateKey;
  }

  @Override
  public String toString() {
    return "ServiceAccountKey[" + clientEmail + "]";
  }

  private static String text(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || !node.isString() || node.asString().isBlank()) {
      return null;
    }
    return node.asString();
  }

  private static RSAPrivateKey readPrivateKey(String pem) {
    String base64 =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    try {
      byte[] der = Base64.getDecoder().decode(base64);
      return (RSAPrivateKey)
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
      throw new ValidationException(
          INVALID + " (private_key ist kein RSA-Schlüssel im PEM-Format)");
    }
  }
}
