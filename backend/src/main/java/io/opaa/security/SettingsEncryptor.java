package io.opaa.security;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Encrypts/decrypts secrets carried by managed system settings at rest - model API keys, the SMTP
 * password, waiting mails - in the formats of {@link EncryptionKeyring} (ADR-0064). The keys come
 * from {@link SettingsEncryptionProperties} ({@code OPAA_SETTINGS_ENCRYPTION_KEY}, decrypt-only
 * {@code OPAA_SETTINGS_ENCRYPTION_PREVIOUS_KEYS}, Base64) and are deliberately separate from {@link
 * CredentialsEncryptor}'s: the two protect different kinds of secret with no reason to share a
 * rotation schedule. Every call names the {@link CipherBinding} of the row the value belongs to.
 * Every stored value was written by this class, so there is no legacy-cleartext fallback. Once
 * {@link LegacyCipherRetirement} retires the unbound form, an {@code enc:v1:} value is refused.
 *
 * <p>The key is validated lazily, on first use, not at startup: most deployments never store a
 * model API key, and a startup check would refuse to start them for a variable they never need.
 */
@Component
public class SettingsEncryptor {

  static final String VARIABLE = "OPAA_SETTINGS_ENCRYPTION_KEY";
  static final String PREVIOUS_VARIABLE = "OPAA_SETTINGS_ENCRYPTION_PREVIOUS_KEYS";
  private static final String DOC_PAGE = "docs/handbuch/betrieb/modelle.md";
  private static final Logger log = LoggerFactory.getLogger(SettingsEncryptor.class);

  private final SettingsEncryptionProperties properties;
  private final CipherFormatGate formatGate;
  private final LegacyCipherRetirement retirement;

  /** Refused values already named in the log of this process, at most {@link #REPORT_LIMIT}. */
  private final Set<CipherBinding> reported = ConcurrentHashMap.newKeySet();

  static final int REPORT_LIMIT = 1_000;

  public SettingsEncryptor(SettingsEncryptionProperties properties, CipherFormatGate formatGate) {
    this(properties, formatGate, LegacyCipherRetirement.NEVER);
  }

  @Autowired
  public SettingsEncryptor(
      SettingsEncryptionProperties properties,
      CipherFormatGate formatGate,
      LegacyCipherRetirement retirement) {
    this.properties = properties;
    this.formatGate = formatGate;
    this.retirement = retirement;
  }

  /**
   * Encrypts {@code plaintext} for the row of {@code binding}, or returns {@code null} if blank (an
   * API key is optional, see {@code
   * docs/features/llm-integration.md#ein-anbindungsweg-nicht-zwei}). Two calls with the same
   * plaintext produce different ciphertexts, because each call draws its own random IV.
   */
  public String encrypt(String plaintext, CipherBinding binding) {
    if (!StringUtils.hasText(plaintext)) {
      return null;
    }
    EncryptionKeyring keyring = requireKeyring();
    try {
      return keyring.encrypt(plaintext, binding, writesKeyed());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Der Zugangsschlüssel konnte nicht verschlüsselt werden", e);
    }
  }

  /** Keyed while the gate is open, and always once the unbound form is refused. */
  private boolean writesKeyed() {
    return formatGate.writesKeyedFormat() || retirement.refusesLegacy(EncryptionPurpose.SETTINGS);
  }

  /**
   * Decrypts a value {@link #encrypt} wrote for the row of {@code binding}, or returns {@code null}
   * if blank. Throws {@link IllegalStateException} for an unknown format version, a
   * corrupted/non-Base64 blob, a value of another row, a wrong/missing key, or an {@code enc:v1:}
   * value once the unbound form is retired - never returns plaintext-looking garbage.
   */
  public String decrypt(String stored, CipherBinding binding) {
    if (!StringUtils.hasText(stored)) {
      return null;
    }
    if (!EncryptionKeyring.isKnownFormat(stored)) {
      throw new IllegalStateException(
          "Gespeicherter Zugangsschlüssel hat ein unbekanntes Verschlüsselungsformat");
    }
    if (stored.startsWith(EncryptionKeyring.V1_PREFIX)
        && retirement.refusesLegacy(EncryptionPurpose.SETTINGS)) {
      if (reported.size() < REPORT_LIMIT && reported.add(binding)) {
        log.warn("Stored secret {} is in the retired form enc:v1 and is refused", binding);
      }
      throw new IllegalStateException(
          "Gespeicherter Zugangsschlüssel liegt im nicht mehr zugelassenen Format enc:v1 vor und"
              + " wird nicht verwendet; er muss neu eingetragen werden. Siehe "
              + DOC_PAGE
              + ".");
    }
    EncryptionKeyring keyring = requireKeyring();
    try {
      return keyring.decrypt(stored, binding);
    } catch (EncryptionKeyring.UnknownKeyException e) {
      throw new IllegalStateException(
          "Zugangsschlüssel konnte nicht entschlüsselt werden - er ist mit einem Schlüssel"
              + " verschlüsselt, der weder in "
              + VARIABLE
              + " noch in "
              + PREVIOUS_VARIABLE
              + " steht. Siehe docs/handbuch/betrieb/netz-und-sicherheit.md.",
          e);
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      throw new IllegalStateException(
          "Zugangsschlüssel konnte nicht entschlüsselt werden - falscher oder fehlender"
              + " Verschlüsselungsschlüssel oder beschädigter Wert",
          e);
    }
  }

  /**
   * The value {@code stored} of the row {@code from}, encrypted anew for the row {@code to} - for a
   * secret that moves to another row or table, where its old binding would no longer authenticate.
   * Blank yields {@code null}. Fails like {@link #decrypt}.
   */
  public String rebind(String stored, CipherBinding from, CipherBinding to) {
    return encrypt(decrypt(stored, from), to);
  }

  /** As {@link CredentialsEncryptor#isKeyed}: a value bound to its row. */
  public static boolean isKeyed(String stored) {
    return CredentialsEncryptor.isKeyed(stored);
  }

  /**
   * {@code stored} under the current key, or {@code stored} itself if it is there already; for
   * {@link CiphertextRewrap}, which asks {@link CipherFormatGate} itself. With {@code keyed} the
   * result is in the keyed format; without, the format is kept: an {@code enc:v1:} value stays
   * unbound, so a version without the keyed format reads it with the current key.
   */
  String rewrap(String stored, CipherBinding binding, boolean keyed) {
    EncryptionKeyring keyring = requireKeyring();
    if (keyring.isCurrent(stored)) {
      return stored;
    }
    boolean toKeyed = keyed || isKeyed(stored);
    if (!toKeyed && keyring.isCurrentUnkeyed(stored)) {
      return stored;
    }
    String plaintext = decrypt(stored, binding);
    try {
      return keyring.encrypt(plaintext, binding, toKeyed);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Der Zugangsschlüssel konnte nicht verschlüsselt werden", e);
    }
  }

  /** Whether decrypt-only keys of an earlier rotation are set. */
  boolean hasPreviousKeys() {
    return properties.previousKeys().stream().anyMatch(StringUtils::hasText);
  }

  /** The id of the current key, as the keyed format names it. */
  String currentKeyId() {
    return requireKeyring().currentKeyId();
  }

  /** Whether a current key is set at all; without one, nothing was ever stored with it. */
  boolean hasKey() {
    return StringUtils.hasText(properties.encryptionKey());
  }

  private EncryptionKeyring requireKeyring() {
    if (!hasKey()) {
      throw new IllegalStateException(
          VARIABLE
              + " ist nicht gesetzt. Diese Umgebungsvariable ist erforderlich, sobald ein"
              + " Zugangsschlüssel für ein Modell, das SMTP-Passwort oder eine wartende Mail"
              + " verschlüsselt oder entschlüsselt werden soll. Siehe "
              + DOC_PAGE
              + ".");
    }
    try {
      return EncryptionKeyring.of(
          properties.encryptionKey(),
          properties.previousKeys(),
          VARIABLE,
          PREVIOUS_VARIABLE,
          DOC_PAGE);
    } catch (InvalidKeyException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }
}
