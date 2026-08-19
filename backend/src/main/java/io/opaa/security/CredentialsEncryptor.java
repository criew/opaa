package io.opaa.security;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Encrypts/decrypts {@code KnowledgeLibrary.sourceCredentials} at rest (#483, ADR-0018 Entscheidung
 * 4) - AES-256-GCM with a random 12-byte IV per call, key from {@link
 * CredentialsEncryptionProperties} ({@code OPAA_CREDENTIALS_ENCRYPTION_KEY}, Base64).
 *
 * <p><b>Stored format:</b> {@code "enc:v1:" + base64(iv || ciphertext || tag)} - GCM appends its
 * 16-byte authentication tag to the ciphertext itself ({@link Cipher#doFinal}), so the stored blob
 * needs no separate tag field. The {@code "enc:v1:"} prefix distinguishes a value this class wrote
 * from a pre-#483 cleartext value already sitting in the column (see {@link #decrypt}) and gives
 * later format changes a version to branch on.
 *
 * <p><b>Legacy cleartext:</b> Every {@code sourceCredentials} value written before this issue is
 * plain {@code user:password} text with no such prefix - migrating it in place is not possible
 * (Liquibase runs before the application, and only the application holds the key). {@link #decrypt}
 * therefore treats any value without the prefix as legacy cleartext and returns it unchanged; the
 * next {@link #encrypt} call on that same row (any credential rotation via the existing update API)
 * encrypts it, requiring no separate migration step or downtime.
 */
@Component
public class CredentialsEncryptor {

  static final String PREFIX = "enc:v1:";
  private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";
  private static final int GCM_IV_LENGTH_BYTES = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final int REQUIRED_KEY_LENGTH_BYTES = 32; // AES-256

  /**
   * Publishes the Spring-managed singleton (real, environment/{@code application.yml}-backed
   * properties) for {@code io.opaa.library.SourceCredentialsConverter} to find - Hibernate
   * instantiates {@code AttributeConverter}s itself via their no-arg constructor rather than asking
   * Spring for the bean {@code @Convert} names (this codebase's Hibernate/Spring Boot combination
   * does not wire {@code hibernate.resource.beans.container} to Spring's bean container), so
   * constructor injection into that converter cannot work. This static, "publish once Spring's
   * container has actually finished creating me" bridge (see {@link #publish}) is the workaround:
   * since {@link CredentialsEncryptor} is itself a singleton {@link Component}, Spring creates
   * exactly one during context refresh, well before any real request can reach {@code
   * SourceCredentialsConverter}. Deliberately populated from {@link #publish} (a {@link
   * PostConstruct} method, which only Spring's bean lifecycle ever calls), not from the constructor
   * itself - {@code CredentialsEncryptorTest} constructs many plain, non-Spring instances directly
   * (with intentionally missing/invalid keys, to test {@link #requireKey()}'s failure modes), and
   * registering unconditionally from the constructor would let the last one of those silently
   * overwrite whatever a real application context had published.
   */
  private static final AtomicReference<CredentialsEncryptor> SPRING_MANAGED_INSTANCE =
      new AtomicReference<>();

  private final CredentialsEncryptionProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();

  public CredentialsEncryptor(CredentialsEncryptionProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void publish() {
    SPRING_MANAGED_INSTANCE.set(this);
  }

  /**
   * Returns the Spring-managed singleton if one has already been published (true for every real
   * application context, see {@link #SPRING_MANAGED_INSTANCE}'s Javadoc), otherwise a bare instance
   * reading {@code OPAA_CREDENTIALS_ENCRYPTION_KEY} directly from the process environment - the
   * fallback used by {@code SourceCredentialsConverter}'s no-arg constructor when no Spring context
   * exists at all (or has not finished creating this bean yet, e.g. a {@code @DataJpaTest}-style
   * slice that never imports anything from {@code io.opaa.security}, so the bean never gets created
   * at all).
   */
  public static CredentialsEncryptor current() {
    CredentialsEncryptor springManaged = SPRING_MANAGED_INSTANCE.get();
    if (springManaged != null) {
      return springManaged;
    }
    return new CredentialsEncryptor(
        new CredentialsEncryptionProperties(System.getenv("OPAA_CREDENTIALS_ENCRYPTION_KEY")));
  }

  /**
   * Encrypts {@code plaintext}, or returns it unchanged if blank ({@code null}/empty carry no
   * credentials to protect - {@code UPLOAD}/{@code FILESYSTEM} libraries always have a {@code null}
   * {@code sourceCredentials}, see {@code chk_knowledge_libraries_source_configuration}). Requires
   * a valid key even for the shortest non-blank value - see class Javadoc "Legacy cleartext" for
   * why an already-encrypted value never reaches this method with its key missing at write time in
   * practice, and {@link CredentialsEncryptionKeyMissingException}'s Javadoc for the failure this
   * method raises when it is.
   */
  public String encrypt(String plaintext) {
    if (!StringUtils.hasText(plaintext)) {
      return plaintext;
    }
    SecretKeySpec key = requireKey();
    byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
    secureRandom.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
      return PREFIX + Base64.getEncoder().encodeToString(combined);
    } catch (GeneralSecurityException e) {
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten konnten nicht verschluesselt werden", e);
    }
  }

  /**
   * Decrypts a value previously produced by {@link #encrypt}, or returns it unchanged if it carries
   * no {@link #PREFIX} - see class Javadoc "Legacy cleartext". Blank input is returned unchanged,
   * the mirror of {@link #encrypt}'s own blank handling.
   */
  public String decrypt(String stored) {
    if (!StringUtils.hasText(stored) || !stored.startsWith(PREFIX)) {
      return stored;
    }
    SecretKeySpec key = requireKey();
    byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
    if (combined.length <= GCM_IV_LENGTH_BYTES) {
      throw new CredentialsEncryptionKeyMissingException(
          "Gespeicherter Zugangsdaten-Wert ist beschaedigt");
    }
    byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH_BYTES);
    byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_LENGTH_BYTES, combined.length);
    try {
      Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten konnten nicht entschluesselt werden - falscher oder fehlender"
              + " Verschluesselungsschluessel",
          e);
    }
  }

  private SecretKeySpec requireKey() {
    String base64Key = properties.encryptionKey();
    if (!StringUtils.hasText(base64Key)) {
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten koennen nicht gespeichert oder gelesen werden: "
              + "opaa.security.credentials.encryption-key (OPAA_CREDENTIALS_ENCRYPTION_KEY) ist"
              + " nicht gesetzt. Siehe docs/deployment.md.");
    }
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key);
    } catch (IllegalArgumentException e) {
      throw new CredentialsEncryptionKeyMissingException(
          "OPAA_CREDENTIALS_ENCRYPTION_KEY ist kein gueltiger Base64-Wert. Siehe"
              + " docs/deployment.md.",
          e);
    }
    if (keyBytes.length != REQUIRED_KEY_LENGTH_BYTES) {
      throw new CredentialsEncryptionKeyMissingException(
          "OPAA_CREDENTIALS_ENCRYPTION_KEY muss ein Base64-kodierter 256-Bit-Schluessel (32 Byte)"
              + " sein. Siehe docs/deployment.md.");
    }
    return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
  }
}
