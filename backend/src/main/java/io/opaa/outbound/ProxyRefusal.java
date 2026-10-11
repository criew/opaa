package io.opaa.outbound;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes the outbound proxy refusing a tunnel ({@code CONNECT}) in the exceptions of the HTTP
 * clients in use and words it for the person who configured the target.
 */
public final class ProxyRefusal {

  /** The JDK {@code HttpClient}, OkHttp and Apache HttpClient 5, in that order. */
  private static final List<Pattern> TUNNEL_REFUSED =
      List.of(
          Pattern.compile("^Tunnel failed, got: (\\d{3})"),
          Pattern.compile("^Unexpected response code for CONNECT: (\\d{3})"),
          Pattern.compile("^CONNECT refused by proxy: HTTP/[\\d.]+ (\\d{3})"));

  private static final String AUTHENTICATION_REQUIRED = "Failed to authenticate with proxy";

  private ProxyRefusal() {}

  /** The German message when {@code failure} or one of its causes is a refused tunnel. */
  public static Optional<String> describe(IOException failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      String message = cause.getMessage();
      if (message == null) {
        continue;
      }
      if (message.startsWith(AUTHENTICATION_REQUIRED)) {
        return Optional.of(message(407));
      }
      for (Pattern pattern : TUNNEL_REFUSED) {
        Matcher matcher = pattern.matcher(message);
        if (matcher.find()) {
          return Optional.of(message(Integer.parseInt(matcher.group(1))));
        }
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return Optional.empty();
  }

  private static String message(int status) {
    String reason =
        status == 407
            ? " (Status 407: der Proxy verlangt eine Anmeldung, die OPAA nicht unterstützt)"
            : " (Status " + status + ")";
    return "Der Ausgangsproxy hat die Verbindung zum Ziel abgelehnt"
        + reason
        + ". Ein Ziel, das direkt erreicht werden soll, gehört auf die Ausnahmeliste des"
        + " Ausgangsproxys.";
  }
}
