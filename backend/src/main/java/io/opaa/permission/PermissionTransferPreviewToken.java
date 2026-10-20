package io.opaa.permission;

import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The preview id of a permission transfer as a signed statement instead of a stored row (ADR-0053,
 * Entscheidung 10): who was shown which order, the print of the rows it showed, and until when it
 * may be acted upon - HMAC-signed with the key of {@link Purpose#PERMISSION_TRANSFER_PREVIEW}, so
 * any instance can check what another one issued. Nothing in it is secret; the signature only makes
 * it unforgeable. A confirmation after the transfer finds the print changed and is refused.
 */
@Component
class PermissionTransferPreviewToken {

  /** What a presented preview id amounts to against the order and state at hand. */
  enum Check {
    /** Issued to this caller for this order, unexpired, and the rows are still the shown ones. */
    VALID,
    /** Missing, forged, expired, or issued to another caller or for another order. */
    NONE,
    /** Valid in every respect but the print: the rows changed since the preview. */
    OUTDATED
  }

  private static final String VERSION = "v1";
  private static final String FIELD_SEPARATOR = "|";
  private static final Pattern FIELDS = Pattern.compile(Pattern.quote(FIELD_SEPARATOR));
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final LocalAuthKeyService keys;

  PermissionTransferPreviewToken(LocalAuthKeyService keys) {
    this.keys = keys;
  }

  /** The preview id for {@code callerId}, {@code order} and {@code fingerprint}. */
  String issue(UUID callerId, String order, String fingerprint, Instant validUntil) {
    String payload =
        String.join(
            FIELD_SEPARATOR,
            VERSION,
            callerId.toString(),
            order,
            fingerprint,
            Long.toString(validUntil.getEpochSecond()));
    return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + "."
        + signatureOf(payload);
  }

  /** Checks {@code previewId} against the caller, the order and the rows standing {@code now}. */
  Check check(String previewId, UUID callerId, String order, String fingerprint, Instant now) {
    if (previewId == null) {
      return Check.NONE;
    }
    int dot = previewId.indexOf('.');
    if (dot <= 0 || dot != previewId.lastIndexOf('.')) {
      return Check.NONE;
    }
    String payload;
    try {
      payload = new String(DECODER.decode(previewId.substring(0, dot)), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException notBase64) {
      return Check.NONE;
    }
    byte[] presented = previewId.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
    byte[] expected = signatureOf(payload).getBytes(StandardCharsets.US_ASCII);
    if (!MessageDigest.isEqual(presented, expected)) {
      return Check.NONE;
    }
    String[] fields = FIELDS.split(payload, -1);
    if (fields.length != 5 || !VERSION.equals(fields[0])) {
      return Check.NONE;
    }
    if (!callerId.toString().equals(fields[1]) || !order.equals(fields[2])) {
      return Check.NONE;
    }
    long validUntil;
    try {
      validUntil = Long.parseLong(fields[4]);
    } catch (NumberFormatException malformed) {
      return Check.NONE;
    }
    if (now.getEpochSecond() > validUntil) {
      return Check.NONE;
    }
    return fingerprint.equals(fields[3]) ? Check.VALID : Check.OUTDATED;
  }

  private String signatureOf(String payload) {
    return keys.lookupHash(Purpose.PERMISSION_TRANSFER_PREVIEW, payload);
  }
}
