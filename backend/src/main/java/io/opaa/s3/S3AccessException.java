package io.opaa.s3;

import io.opaa.sourceaccess.UntrustedCertificate;
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

  /**
   * {@code 403} while listing or heading a bucket: {@code s3:ListBucket} is missing, or the {@code
   * permission} the listing needs ({@code s3:ListBucketVersions} for the versions).
   */
  public static final class ListForbidden extends S3AccessException {
    public ListForbidden(String bucket) {
      this(bucket, "s3:ListBucket");
    }

    public ListForbidden(String bucket, String permission) {
      super(
          "Der Bucket „"
              + bucket
              + "“ darf mit diesen Zugangsdaten nicht aufgelistet werden ("
              + permission
              + " fehlt).");
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

  /**
   * {@code 403} while putting or deleting an object: {@code permission} ({@code s3:PutObject},
   * {@code s3:DeleteObject}) is missing.
   */
  public static final class WriteForbidden extends S3AccessException {
    public WriteForbidden(String bucket, String key, String permission) {
      super(
          "Das Objekt „"
              + bucket
              + "/"
              + key
              + "“ darf mit diesen Zugangsdaten nicht geschrieben werden ("
              + permission
              + " fehlt).");
    }
  }

  /**
   * The bucket is not there. {@link #confirmed()} only when the store named the error code {@code
   * NoSuchBucket}; a bare {@code 404} may come from a proxy or ingress in front of the store.
   */
  public static final class BucketNotFound extends S3AccessException {
    private final boolean confirmed;

    public BucketNotFound(String bucket) {
      this(bucket, false);
    }

    public BucketNotFound(String bucket, boolean confirmed) {
      super(
          "Der Bucket „"
              + bucket
              + "“ existiert nicht oder ist über diesen Endpoint nicht erreichbar.");
      this.confirmed = confirmed;
    }

    public boolean confirmed() {
      return confirmed;
    }
  }

  /**
   * No current object under the key - the positive finding a deletion needs (Entscheidung 3).
   * {@link #confirmed()} only when the store named {@code NoSuchKey} in a body; a HEAD answer has
   * none, and a bare {@code 404} may come from a proxy or ingress in front of the store.
   */
  public static final class ObjectNotFound extends S3AccessException {
    private final boolean confirmed;

    public ObjectNotFound(String bucket, String key) {
      this(bucket, key, true);
    }

    public ObjectNotFound(String bucket, String key, boolean confirmed) {
      super("Das Objekt „" + bucket + "/" + key + "“ existiert nicht.");
      this.confirmed = confirmed;
    }

    public boolean confirmed() {
      return confirmed;
    }
  }

  /** {@code 301 PermanentRedirect} or a malformed authorization: region or addressing style. */
  public static final class WrongRegionOrStyle extends S3AccessException {
    /** The bucket-free form, for the bucket listing. */
    public WrongRegionOrStyle() {
      super(
          "Der Objektspeicher verweist auf einen anderen Endpoint: Region oder Adressstil"
              + " (Path-Style/Virtual-Host) passen nicht zur Konfiguration.");
    }

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

  public static final class ObjectTooLarge extends S3AccessException {
    public ObjectTooLarge(String bucket, String key, long maxBytes) {
      super(describe(bucket, key, maxBytes));
    }

    /** The sentence, for a caller that decides from the listed size without a download. */
    public static String describe(String bucket, String key, long maxBytes) {
      return "Das Objekt „"
          + bucket
          + "/"
          + key
          + "“ überschreitet die Größenobergrenze von "
          + maxBytes
          + " Bytes und wurde nicht übernommen.";
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

  /** A failed TLS handshake; an untrusted certificate is named with its remedy. */
  public static final class Tls extends S3AccessException {
    public Tls() {
      this(false);
    }

    public Tls(boolean untrustedCertificate) {
      super(
          untrustedCertificate
              ? "Die TLS-Verbindung zum Objektspeicher ist fehlgeschlagen. "
                  + UntrustedCertificate.MESSAGE
              : "Die TLS-Verbindung zum Objektspeicher ist fehlgeschlagen (Protokollfehler).");
    }
  }

  /** Connection refused, DNS failure or timeout. */
  public static final class Unreachable extends S3AccessException {
    public Unreachable(String detail) {
      super("Der Objektspeicher ist nicht erreichbar: " + detail);
    }

    private Unreachable(String message, boolean verbatim) {
      super(message);
    }

    /**
     * A target name that does not resolve: unreachable, but worded exactly like {@link
     * TargetBlocked}, so the answer does not tell whether an internal name exists.
     */
    public static Unreachable unresolvedTarget(String message, String allowlistHint) {
      return new Unreachable(message + " " + allowlistHint, true);
    }
  }

  /**
   * The target-address validation refused the endpoint or a bucket host; the message names the
   * allowlist variable of the side that owns the client (ADR-0027, Entscheidung 8; ADR-0030,
   * Entscheidung 8).
   */
  public static final class TargetBlocked extends S3AccessException {
    public TargetBlocked(String message, String allowlistHint) {
      super(message + " " + allowlistHint);
    }
  }

  /**
   * {@code 501 NotImplemented}: the store does not offer the operation at all - an S3-compatible
   * store without the version API, for instance.
   */
  public static final class NotImplemented extends S3AccessException {
    public NotImplemented(String operation) {
      super("Der Objektspeicher unterstützt " + operation + " nicht (NotImplemented).");
    }
  }

  /** A listing answered "truncated" without a continuation token - it cannot be completed. */
  public static final class ListingIncomplete extends S3AccessException {
    public ListingIncomplete(String bucket) {
      super(
          "Der Objektspeicher meldet für den Bucket „"
              + bucket
              + "“ eine abgeschnittene Auflistung ohne Fortsetzungstoken; die Auflistung kann"
              + " nicht vervollständigt werden.");
    }
  }
}
