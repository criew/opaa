package io.opaa.externalaccess;

import java.util.List;

/**
 * The delivered channel settings of the external access (docs/features/external-access.md, "Der
 * Schalter der Installation"). They are the seeded values of the singleton row, not a fallback
 * resolved at read time: every one of them is a policy an administrator either accepted or changed.
 * The bounds are enforced twice - here and by the table's CHECKs.
 */
public final class ExternalAccessDefaults {

  /** A fresh installation behaves as if the channel did not exist. */
  public static final boolean ENABLED = false;

  public static final int TOKEN_MAX_LIFETIME_DAYS = 90;

  /**
   * Deliberately conservative and, as external-access.md says, guessed until a pilot installation
   * has measured it once: the quota is a load brake, not a protection against mass retrieval.
   */
  public static final int TOKEN_RATE_LIMIT_PER_HOUR = 60;

  /** A multiple of the expected channel volume, not a busy-day threshold. */
  public static final int MASS_RETRIEVAL_ALERT_THRESHOLD = 600;

  /**
   * "Hausnetz": the private address space plus loopback - the whole channel, not a single token.
   */
  public static final List<String> ALLOWED_CIDRS =
      List.of(
          "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "::1/128", "fc00::/7");

  /**
   * The delivered instructions text of the MCP initialisation; resettable in the administration.
   */
  public static final String SERVER_INSTRUCTIONS =
      "Bei Fragen zu Verwaltungsvorgängen dieser Behörde zuerst „search“ aufrufen. Antworten mit"
          + " Fundstellen belegen und die Herkunft angeben (Bibliothek, Dokument, Fundstelle)."
          + " Nichts erfinden, was die Treffer nicht hergeben.";

  public static final int MIN_TOKEN_MAX_LIFETIME_DAYS = 1;
  public static final int MAX_TOKEN_MAX_LIFETIME_DAYS = 365;
  public static final int MIN_TOKEN_RATE_LIMIT_PER_HOUR = 1;
  public static final int MAX_TOKEN_RATE_LIMIT_PER_HOUR = 10_000;
  public static final int MIN_MASS_RETRIEVAL_ALERT_THRESHOLD = 1;
  public static final int MAX_MASS_RETRIEVAL_ALERT_THRESHOLD = 1_000_000;
  public static final int MAX_ALLOWED_CIDRS = 50;
  public static final int MAX_SERVER_INSTRUCTIONS_LENGTH = 4000;

  private ExternalAccessDefaults() {}
}
