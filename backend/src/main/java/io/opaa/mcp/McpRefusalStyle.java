package io.opaa.mcp;

import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.externalaccess.security.ExternalAccessRefusalStyle;
import io.opaa.externalaccess.token.ExternalAccessTokenRejection;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * How the MCP endpoint answers a refused call (ADR-0035), with three codes:
 *
 * <ul>
 *   <li>switch off, usable token - {@code 503} naming the cause, so operations and the person
 *       setting a client up can tell "Kanal zu" from "falscher Pfad" and from "Proxy kaputt";
 *   <li>switch off, anything else - {@code 404}, which does not confirm that the service exists;
 *   <li>switch on - the {@code 401} of the rest of the channel, with the reason in {@code
 *       WWW-Authenticate}: a tool whose token expired needs to see that, not a missing page.
 * </ul>
 */
@ConditionalOnFeature(Feature.EXTERNAL_ACCESS)
@Component
class McpRefusalStyle implements ExternalAccessRefusalStyle {

  static final String CLOSED_MESSAGE =
      "Der Fremdzugang dieser Installation ist zurzeit ausgeschaltet.";

  private final McpEndpoint endpoint;
  private final ExternalAccessSettingsService settings;
  private final JsonMapper jsonMapper;
  private final Clock clock;

  McpRefusalStyle(
      McpEndpoint endpoint,
      ExternalAccessSettingsService settings,
      JsonMapper jsonMapper,
      Clock clock) {
    this.endpoint = endpoint;
    this.settings = settings;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  @Override
  public boolean appliesTo(HttpServletRequest request) {
    return endpoint.matches(request);
  }

  @Override
  public boolean distinguishesClosedChannel() {
    return true;
  }

  @Override
  public void refuse(HttpServletResponse response, ExternalAccessTokenRejection rejection)
      throws IOException {
    if (rejection == ExternalAccessTokenRejection.CHANNEL_CLOSED) {
      write(response, HttpStatus.SERVICE_UNAVAILABLE, CLOSED_MESSAGE, rejection);
      return;
    }
    if (!settings.isEnabled()) {
      write(response, HttpStatus.NOT_FOUND, "Nicht gefunden", null);
      return;
    }
    response.setHeader(
        HttpHeaders.WWW_AUTHENTICATE,
        "Bearer error=\"invalid_token\", error_description=\"" + rejection.marker() + "\"");
    write(response, HttpStatus.UNAUTHORIZED, "Nicht angemeldet", rejection);
  }

  /**
   * The body carries the marker as {@code reason}: on a {@code 401} it repeats {@code
   * WWW-Authenticate}, and on the {@code 503} it is the only machine-readable place for it - that
   * header belongs to an authentication challenge, which a closed channel is not.
   */
  private void write(
      HttpServletResponse response,
      HttpStatus status,
      String message,
      ExternalAccessTokenRejection rejection)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", message);
    body.put("status", status.value());
    body.put("timestamp", Instant.now(clock).toString());
    if (rejection != null) {
      body.put("reason", rejection.marker());
    }
    jsonMapper.writeValue(response.getOutputStream(), body);
  }
}
