package io.opaa.indexing.source.s3;

import java.io.IOException;

/**
 * Base of every failure the S3 access layer reports. {@link #getMessage()} is a German, user-facing
 * sentence fit for a run event or the connection test - it names bucket, key and the store's error
 * code, never a credential, a signature or a raw upstream message; no cause is ever attached,
 * because the SDK's own messages can carry the request URL and the access key id (guarded by {@code
 * AwsSdkS3ObjectStoreTest#mapsEveryFailureToAGermanMessageWithoutCredentials} and {@code
 * S3LogLeakTest}).
 */
public class S3AccessException extends IOException {

  /**
   * Appended to a target-validation rejection so whoever configures an internal object store learns
   * which setting unblocks it (ADR-0027, Entscheidung 8).
   */
  public static final String ALLOWLIST_HINT =
      "Interne Adressen gibt der Betrieb über OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST frei.";

  public S3AccessException(String message) {
    super(message);
  }

  /** The store rejected the access key, the signature or an expired session token. */
  public static final class Authentication extends S3AccessException {
    public Authentication(String code) {
      super(
          "Der Objektspeicher hat die Zugangsdaten abgelehnt (Access Key oder Secret Key ungültig,"
              + " Session-Token abgelaufen"
              + (code == null ? "" : "; " + code)
              + ").");
    }
  }

  /** {@code 403} while listing or heading a bucket: {@code s3:ListBucket} is missing. */
  public static final class ListForbidden extends S3AccessException {
    public ListForbidden(String bucket) {
      super(
          "Der Bucket „"
              + bucket
              + "“ darf mit diesen Zugangsdaten nicht aufgelistet werden (s3:ListBucket fehlt).");
    }
  }

  /** {@code 403} while heading or getting an object: {@code s3:GetObject} is missing. */
  public static final class ReadForbidden extends S3AccessException {
    public ReadForbidden(String bucket, String key) {
      super(
          "Das Objekt „"
              + bucket
              + "/"
              + key
              + "“ darf mit diesen Zugangsdaten nicht gelesen werden (s3:GetObject fehlt).");
    }
  }

  public static final class BucketNotFound extends S3AccessException {
    public BucketNotFound(String bucket) {
      super(
          "Der Bucket „"
              + bucket
              + "“ existiert nicht oder ist über diesen Endpoint nicht erreichbar.");
    }
  }

  /** No current object under the key - the positive finding a deletion needs (Entscheidung 3). */
  public static final class ObjectNotFound extends S3AccessException {
    public ObjectNotFound(String bucket, String key) {
      super("Das Objekt „" + bucket + "/" + key + "“ existiert nicht.");
    }
  }

  /** {@code 301 PermanentRedirect} or a malformed authorization: region or addressing style. */
  public static final class WrongRegionOrStyle extends S3AccessException {
    public WrongRegionOrStyle(String bucket) {
      super(
          "Der Objektspeicher verweist für den Bucket „"
              + bucket
              + "“ auf einen anderen Endpoint: Region oder Adressstil (Path-Style/Virtual-Host)"
              + " passen nicht zur Konfiguration.");
    }
  }

  public static final class ClockSkew extends S3AccessException {
    public ClockSkew() {
      super(
          "Der Objektspeicher lehnt die Anfrage wegen Zeitabweichung ab (RequestTimeTooSkewed):"
              + " Die Uhr des OPAA-Servers weicht zu weit von der des Speichers ab.");
    }
  }

  /** {@code 503 SlowDown}/{@code 429} kept coming after every retry the configuration allows. */
  public static final class RateLimited extends S3AccessException {
    public RateLimited(int retries) {
      super(
          "Der Objektspeicher drosselt die Anfragen (HTTP 503/429) auch nach "
              + retries
              + " Wiederholungen.");
    }
  }

  /**
   * The run's request budget is spent - not a failure of the store or the credentials but the run's
   * own bound; the executor ends the run in an orderly way and the next run continues.
   */
  public static final class BudgetExhausted extends S3AccessException {
    private final int budget;

    public BudgetExhausted(int budget) {
      super(
          "Anfragebudget von "
              + budget
              + " Anfragen für diesen Lauf erschöpft; der nächste Lauf setzt fort");
      this.budget = budget;
    }

    public int budget() {
      return budget;
    }
  }

  public static final class ObjectTooLarge extends S3AccessException {
    public ObjectTooLarge(String bucket, String key, long maxBytes) {
      super(
          "Das Objekt „"
              + bucket
              + "/"
              + key
              + "“ überschreitet die Größenobergrenze von "
              + maxBytes
              + " Bytes und wurde nicht übernommen.");
    }
  }

  /** {@code InvalidObjectState}: the object lies in an archive class without a restore. */
  public static final class Archived extends S3AccessException {
    public Archived(String bucket, String key) {
      super(
          "Das Objekt „"
              + bucket
              + "/"
              + key
              + "“ liegt in einer Archivklasse und ist ohne Wiederherstellung nicht lesbar.");
    }
  }

  public static final class Tls extends S3AccessException {
    public Tls() {
      super(
          "Die TLS-Verbindung zum Objektspeicher ist fehlgeschlagen (Zertifikat nicht"
              + " vertrauenswürdig oder Protokollfehler).");
    }
  }

  /** Connection refused, DNS failure or timeout. */
  public static final class Unreachable extends S3AccessException {
    public Unreachable(String detail) {
      super("Der Objektspeicher ist nicht erreichbar: " + detail);
    }
  }

  /** The target-address validation refused the endpoint, the proxy or a bucket host. */
  public static final class TargetBlocked extends S3AccessException {
    public TargetBlocked(String message) {
      super(message + " " + ALLOWLIST_HINT);
    }
  }
}
