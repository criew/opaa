package io.opaa.installation;

import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The target address check of the mail step (ADR-0060, Entscheidung 2): before any account exists
 * only the token guards the installation, so its mail server must not resolve to an internal,
 * loopback or link-local address unless the operator released the host in {@code
 * OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST}. The administration checks no mail server address.
 */
@Component
class InstallationMailTargets {

  static final String REFUSED =
      "Der Mailserver ist nicht zugelassen oder nicht erreichbar. Einen internen Mailserver gibt"
          + " der Betrieb für die Installation über "
          + InstallationProperties.MAIL_TARGET_ALLOWLIST_VARIABLE
          + " frei; ohne Freigabe den Schritt überspringen und den Server nach der Installation"
          + " unter „Administration → E-Mail“ eintragen.";

  private static final Logger log = LoggerFactory.getLogger(InstallationMailTargets.class);

  private final TargetAddressValidator validator;

  InstallationMailTargets(InstallationProperties properties) {
    this.validator = new TargetAddressValidator(true, properties.mailTargetAllowlist());
  }

  /**
   * Why the check refuses {@code host} on {@code port}, or {@code null}. A blocked range and a name
   * that does not resolve get the same sentence, so the answer does not tell whether an internal
   * name exists; the cause goes to the log only.
   */
  String blockedReason(String host, int port) {
    try {
      validator.validateHost(host, port);
      return null;
    } catch (IOException e) {
      log.warn("Mail server {}:{} refused: {}", host, port, e.getClass().getSimpleName());
      return REFUSED;
    }
  }
}
