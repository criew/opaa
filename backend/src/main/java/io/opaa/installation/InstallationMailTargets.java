package io.opaa.installation;

import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The target address check of the mail step (ADR-0060, Entscheidung 14): before any account exists
 * only the token guards the installation, so its mail server must not resolve to an internal,
 * loopback or link-local address unless the operator released the host in {@code
 * OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST}, and it may use only the usual SMTP ports unless an
 * entry names exactly that {@code host:port}. The administration checks no mail server address.
 */
@Component
class InstallationMailTargets {

  /** The ports a mail server of the assistant may use without an entry naming the port. */
  static final Set<Integer> SMTP_PORTS = Set.of(25, 465, 587, 2525);

  static final String REFUSED =
      "Der Mailserver ist nicht zugelassen oder nicht erreichbar. Einen internen Mailserver gibt"
          + " der Betrieb für die Installation über "
          + InstallationProperties.MAIL_TARGET_ALLOWLIST_VARIABLE
          + " frei; ohne Freigabe den Schritt überspringen und den Server nach der Installation"
          + " unter „Administration → E-Mail“ eintragen.";

  static final String PORT_REFUSED =
      "Im Assistenten sind die Ports 25, 465, 587 und 2525 zugelassen. Einen anderen Port gibt der"
          + " Betrieb mit Host und Port in "
          + InstallationProperties.MAIL_TARGET_ALLOWLIST_VARIABLE
          + " frei; sonst den Server nach der Installation unter „Administration → E-Mail“"
          + " eintragen.";

  private static final Logger log = LoggerFactory.getLogger(InstallationMailTargets.class);

  private final TargetAddressValidator validator;
  private final List<String> allowlist;

  InstallationMailTargets(InstallationProperties properties) {
    this.allowlist = properties.mailTargetAllowlist();
    this.validator = new TargetAddressValidator(true, allowlist);
  }

  /**
   * Why the check refuses {@code host} on {@code port}, or {@code null}. A blocked range and a name
   * that does not resolve get the same sentence, so the answer does not tell whether an internal
   * name exists; the cause goes to the log only, the host stripped of control characters.
   */
  String blockedReason(String host, int port) {
    if (!SMTP_PORTS.contains(port) && !namesExactly(host, port)) {
      return PORT_REFUSED;
    }
    try {
      validator.validateHost(host, port);
      return null;
    } catch (IOException e) {
      log.warn("Mail server {}:{} refused: {}", forLog(host), port, e.getClass().getSimpleName());
      return REFUSED;
    }
  }

  private boolean namesExactly(String host, int port) {
    String wanted = (host + ":" + port).toLowerCase(Locale.ROOT);
    return allowlist.stream().anyMatch(entry -> entry.toLowerCase(Locale.ROOT).equals(wanted));
  }

  /** {@code value} with every control character replaced, so it cannot forge a log line. */
  static String forLog(String value) {
    return value == null ? null : value.replaceAll("\\p{Cntrl}", "?");
  }
}
