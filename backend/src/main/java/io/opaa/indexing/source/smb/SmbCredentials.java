package io.opaa.indexing.source.smb;

/**
 * The service account of a library, from {@code sourceCredentials}: {@code
 * DOMÄNE\Benutzer:Passwort}, {@code Benutzer@domäne:Passwort} or {@code Benutzer:Passwort}, split
 * at the first colon. {@link #toString()} never names the password.
 *
 * @param domain the NTLM domain, {@code ""} when the account names none (a local account or a UPN)
 */
record SmbCredentials(String domain, String username, String password) {

  static final String FORMAT =
      "sourceCredentials müssen dem Format DOMÄNE\\Benutzer:Passwort oder Benutzer:Passwort"
          + " entsprechen";

  /**
   * @throws SmbAddress.InvalidSmbConfigurationException with {@link #FORMAT} for anything else
   */
  static SmbCredentials parse(String credentials) {
    int colon = credentials == null ? -1 : credentials.indexOf(':');
    if (colon <= 0 || colon == credentials.length() - 1) {
      throw new SmbAddress.InvalidSmbConfigurationException(FORMAT);
    }
    String account = credentials.substring(0, colon).trim();
    String password = credentials.substring(colon + 1);
    int backslash = account.indexOf('\\');
    String domain = backslash < 0 ? "" : account.substring(0, backslash).trim();
    String username = backslash < 0 ? account : account.substring(backslash + 1).trim();
    if (username.isEmpty() || (backslash >= 0 && domain.isEmpty())) {
      throw new SmbAddress.InvalidSmbConfigurationException(FORMAT);
    }
    return new SmbCredentials(domain, username, password);
  }

  /** The account as Windows writes it, for messages. */
  String account() {
    return domain.isEmpty() ? username : domain + "\\" + username;
  }

  @Override
  public String toString() {
    return "SmbCredentials[account=" + account() + "]";
  }
}
