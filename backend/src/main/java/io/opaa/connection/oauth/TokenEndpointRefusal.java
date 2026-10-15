package io.opaa.connection.oauth;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The error answer of a token endpoint (RFC 6749, 5.2), read for a German hint the administration
 * can act on: a Microsoft Entra code ({@code error_codes[0]}, else the {@code AADSTS<n>} prefix of
 * {@code error_description}) wins over the OAuth {@code error}. The description itself never
 * reaches a message; which exception follows stays the caller's decision by {@link #error()}.
 */
final class TokenEndpointRefusal {

  static final String INVALID_CLIENT =
      "Client-ID oder Client-Secret werden nicht angenommen. Häufige Ursachen: Tippfehler,"
          + " abgelaufenes Client-Secret, Secret-ID statt Secret-Wert, falscher Mandant.";

  private static final Pattern AADSTS_PREFIX = Pattern.compile("\\AAADSTS(\\d{1,9})\\b");
  private static final Pattern PRINTABLE = Pattern.compile("[\\x21-\\x7E]{1,100}");

  private static final Map<Long, String> ENTRA_HINTS =
      Map.ofEntries(
          Map.entry(
              7000215L,
              "Das Client-Secret wird nicht angenommen. Häufigste Ursache: Eingetragen ist die"
                  + " „Geheime ID“ (eine GUID) statt des „Werts“ (etwa 40 Zeichen, meist mit"
                  + " „~“). Den Wert zeigt Microsoft Entra nur direkt nach dem Anlegen;"
                  + " gegebenenfalls ein neues Client-Secret erzeugen und dessen Wert eintragen."),
          Map.entry(
              7000222L,
              "Das Client-Secret ist abgelaufen. Ein neues Client-Secret in Microsoft Entra"
                  + " erzeugen und dessen Wert am Zugang eintragen."),
          Map.entry(
              700016L,
              "Die Client-ID ist im Mandanten nicht bekannt. Die Anwendungs-ID (Client-ID) der"
                  + " App-Registrierung eintragen, nicht die Objekt-ID, und den Mandanten prüfen."),
          Map.entry(
              90002L, "Der Mandant existiert nicht. Die Verzeichnis-ID (Mandanten-ID) prüfen."),
          Map.entry(
              900023L,
              "Die Angabe des Mandanten ist ungültig. Erwartet wird die Verzeichnis-ID (eine"
                  + " GUID) oder eine Domain wie contoso.onmicrosoft.com."),
          Map.entry(
              7000216L,
              "Der Anfrage fehlt das Client-Secret. Am Zugang ein Client-Secret hinterlegen."),
          Map.entry(
              900144L,
              "Der Anfrage fehlt ein Pflichtparameter. Client-ID, Client-Secret und Scope des"
                  + " Zugangs prüfen."),
          Map.entry(
              7000112L,
              "Die App-Registrierung ist im Mandanten deaktiviert. Sie muss in Microsoft Entra"
                  + " wieder aktiviert werden."),
          Map.entry(
              70011L,
              "Der Scope wird nicht angenommen. Für die Anmeldung als Anwendung gegen Microsoft"
                  + " Graph lautet er https://graph.microsoft.com/.default."),
          Map.entry(
              500011L,
              "Die Ressource des Scopes ist im Mandanten nicht bekannt. Server-Adresse und Scope"
                  + " des Zugangs prüfen."));

  private final String error;
  private final Long entraCode;
  private final String traceId;
  private final String correlationId;

  private TokenEndpointRefusal(String error, Long entraCode, String traceId, String correlationId) {
    this.error = error;
    this.entraCode = entraCode;
    this.traceId = traceId;
    this.correlationId = correlationId;
  }

  /** The refusal {@code body} carries; {@code null} stands for an answer that was no JSON. */
  static TokenEndpointRefusal of(JsonNode body) {
    return new TokenEndpointRefusal(
        text(body, "error"),
        entraCodeOf(body),
        printable(text(body, "trace_id")),
        printable(text(body, "correlation_id")));
  }

  /** The OAuth {@code error}, empty where the answer named none. */
  String error() {
    return error;
  }

  /**
   * The German hint for this refusal, {@code null} where neither a known Entra code nor the error
   * tells more; {@code scope}, where known, is named for {@code invalid_scope}.
   */
  String hint(String scope) {
    String entra = entraCode == null ? null : ENTRA_HINTS.get(entraCode);
    if (entra != null) {
      return entra;
    }
    return switch (error) {
      case "invalid_client" -> INVALID_CLIENT;
      case "unauthorized_client" ->
          "Die App-Registrierung darf diesen Anmeldeweg (Grant-Typ) nicht nutzen.";
      case "invalid_scope" ->
          scope == null
              ? "Der Scope des Zugangs wird nicht angenommen."
              : "Der Scope „" + scope + "“ des Zugangs wird nicht angenommen.";
      default -> null;
    };
  }

  /** What the log line names: error, Entra code and Entra's trace and correlation id. */
  String logDetail() {
    StringBuilder detail =
        new StringBuilder(printable(error) == null ? "no error code" : printable(error));
    if (entraCode != null) {
      detail.append(", AADSTS").append(entraCode);
    }
    if (traceId != null) {
      detail.append(", trace_id ").append(traceId);
    }
    if (correlationId != null) {
      detail.append(", correlation_id ").append(correlationId);
    }
    return detail.toString();
  }

  private static Long entraCodeOf(JsonNode body) {
    JsonNode codes = body == null ? null : body.get("error_codes");
    if (codes != null && codes.isArray() && !codes.isEmpty() && codes.get(0).canConvertToLong()) {
      return codes.get(0).asLong();
    }
    Matcher prefix = AADSTS_PREFIX.matcher(text(body, "error_description"));
    return prefix.find() ? Long.valueOf(prefix.group(1)) : null;
  }

  private static String text(JsonNode body, String name) {
    JsonNode value = body == null ? null : body.get(name);
    return value == null || !value.isString() ? "" : value.asString();
  }

  /** {@code value} where it is one printable token fit for a log line, else {@code null}. */
  private static String printable(String value) {
    return PRINTABLE.matcher(value).matches() ? value : null;
  }
}
