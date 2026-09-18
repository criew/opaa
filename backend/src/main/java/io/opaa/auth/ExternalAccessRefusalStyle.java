package io.opaa.auth;

import io.opaa.externalaccess.token.ExternalAccessTokenRejection;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * How one path of the external-access channel answers a refused call. The default of {@link
 * ExternalAccessTokenAuthenticationFilter} - {@code 401} with the reason in {@code
 * WWW-Authenticate} - applies wherever no style claims the request.
 *
 * <p>Exists because the MCP endpoint owes two different answers a REST endpoint does not (#1721,
 * ADR-0035): a closed channel is {@code 404} without a usable token and {@code 503} with one, so
 * the person setting a client up can tell "Kanal zu" from "falscher Pfad". The seam keeps that
 * knowledge in the feature package instead of turning the filter into a path table.
 */
public interface ExternalAccessRefusalStyle {

  /** Whether this style answers for {@code request}. */
  boolean appliesTo(HttpServletRequest request);

  /**
   * Whether the presented value must be checked <em>before</em> the installation switch, so a
   * refusal of {@link ExternalAccessTokenRejection#CHANNEL_CLOSED} means "this value would work if
   * the channel were open".
   *
   * <p>{@code true} makes the path an oracle for which values exist while the channel is closed -
   * the very thing {@link
   * io.opaa.externalaccess.token.ExternalAccessTokenAuthenticator#authenticate} avoids. Only a path
   * whose specification demands the distinction may return {@code true}.
   */
  default boolean distinguishesClosedChannel() {
    return false;
  }

  /** Writes the refusal. The response is untouched when this is called. */
  void refuse(HttpServletResponse response, ExternalAccessTokenRejection rejection)
      throws IOException;
}
