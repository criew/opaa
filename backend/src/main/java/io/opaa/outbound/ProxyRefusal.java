package io.opaa.outbound;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes the outbound proxy refusing a connection - a refused tunnel ({@code CONNECT}) in the
 * exceptions of the HTTP clients in use, or a {@code 407} that the JDK client hands back as an
 * answer - and words it for the person who configured the target.
 */
public final class ProxyRefusal {

  /** The JDK {@code HttpClient}, OkHttp and Apache HttpClient 5, in that order. */
  private static final List<Pattern> TUNNEL_REFUSED =
      List.of(
          Pattern.compile("^Tunnel failed, got: (\\d{3})"),
          Pattern.compile("^Unexpected response code for CONNECT: (\\d{3})"),
          Pattern.compile("^CONNECT refused by proxy: HTTP/[\\d.]+ (\\d{3})"));

  private static final String AUTHENTICATION_REQUIRED = "Failed to authenticate with proxy";

  private static final int PROXY_AUTHENTICATION_REQUIRED = 407;

  private ProxyRefusal() {}

  /** The German message when {@code failure} or one of its causes is a refused tunnel. */
  public static Optional<String> describe(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof RefusedException refused) {
        return Optional.of(refused.getMessage());
      }
      String message = cause.getMessage();
      if (message == null) {
        continue;
      }
      if (message.startsWith(AUTHENTICATION_REQUIRED)) {
        return Optional.of(message(PROXY_AUTHENTICATION_REQUIRED));
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

  /**
   * The German message for an answer status only a proxy gives: {@code 407} - the JDK client
   * returns it as a response rather than throwing, for a tunnel and a plain request alike.
   */
  public static Optional<String> describeStatus(int status) {
    return status == PROXY_AUTHENTICATION_REQUIRED
        ? Optional.of(message(PROXY_AUTHENTICATION_REQUIRED))
        : Optional.empty();
  }

  /** {@code otherwise} unless {@code failure} or one of its causes is a refused tunnel. */
  public static String failureMessage(Throwable failure, String otherwise) {
    return describe(failure).orElse(otherwise);
  }

  /** {@code otherwise} unless {@code status} is the outbound proxy's {@code 407}. */
  public static String statusMessage(int status, String otherwise) {
    return describeStatus(status).orElse(otherwise);
  }

  /**
   * The failure for an answer {@code status} that is no success: a {@link RefusedException} for the
   * outbound proxy's {@code 407}, otherwise an {@link IOException} with {@code otherwise}.
   */
  public static IOException statusFailure(int status, String otherwise) {
    return status == PROXY_AUTHENTICATION_REQUIRED
        ? new RefusedException(status)
        : new IOException(otherwise);
  }

  /** The outbound proxy refused the connection; the message is German and user-facing. */
  public static final class RefusedException extends IOException {
    public RefusedException(int status) {
      super(message(status));
    }
  }

  private static String message(int status) {
    if (status == PROXY_AUTHENTICATION_REQUIRED) {
      return "Der Ausgangsproxy hat die Verbindung zum Ziel abgelehnt (Status 407): Er verlangt eine"
          + " Anmeldung, die OPAA nicht unterstützt. Der Proxy muss OPAA über die Adresse des"
          + " Servers zulassen.";
    }
    if (status == 502 || status == 503 || status == 504) {
      return "Der Ausgangsproxy hat das Ziel nicht erreicht (Status "
          + status
          + "). Prüfen Sie die Adresse auf Tippfehler. Ein internes Ziel, das direkt erreicht"
          + " werden soll, gehört auf die Ausnahmeliste des Ausgangsproxys.";
    }
    return "Der Ausgangsproxy hat die Verbindung zum Ziel abgelehnt (Status "
        + status
        + "). Lässt der Proxy dieses Ziel nicht zu, gehört ein internes Ziel auf die Ausnahmeliste"
        + " des Ausgangsproxys.";
  }
}
