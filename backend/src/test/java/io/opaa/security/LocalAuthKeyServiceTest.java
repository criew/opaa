package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.security.LocalAuthKeyService.Purpose;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalAuthKeyService}: one 256-bit key per purpose, deterministic for a secret, different
 * across purposes and across secrets (so a rotation of {@code OPAA_AUTH_JWT_SECRET} changes every
 * key at once), plus the two hash helpers the token tables store.
 */
class LocalAuthKeyServiceTest {

  private static final String SECRET = "local-auth-key-service-test-secret-0123456789";

  @Test
  void derivesTheSameKeyForTheSameSecretAndPurpose() {
    LocalAuthKeyService first = new LocalAuthKeyService(SECRET);
    LocalAuthKeyService second = new LocalAuthKeyService(SECRET);

    assertThat(first.key(Purpose.ACCESS_TOKEN).getEncoded())
        .isEqualTo(second.key(Purpose.ACCESS_TOKEN).getEncoded());
  }

  @Test
  void derivesADifferentKeyPerPurpose() {
    LocalAuthKeyService service = new LocalAuthKeyService(SECRET);

    byte[] access = service.key(Purpose.ACCESS_TOKEN).getEncoded();
    byte[] refresh = service.key(Purpose.REFRESH_TOKEN_LOOKUP).getEncoded();
    byte[] action = service.key(Purpose.ACTION_TOKEN_LOOKUP).getEncoded();

    assertThat(access).isNotEqualTo(refresh).isNotEqualTo(action);
    assertThat(refresh).isNotEqualTo(action);
  }

  @Test
  void aRotatedSecretChangesEveryKey() {
    LocalAuthKeyService before = new LocalAuthKeyService(SECRET);
    LocalAuthKeyService after = new LocalAuthKeyService(SECRET + "-rotated");

    for (Purpose purpose : Purpose.values()) {
      assertThat(before.key(purpose).getEncoded()).isNotEqualTo(after.key(purpose).getEncoded());
    }
  }

  @Test
  void keysAre256BitHmacSha256Keys() {
    SecretKey key = new LocalAuthKeyService(SECRET).key(Purpose.ACCESS_TOKEN);

    assertThat(key.getEncoded()).hasSize(32);
    assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
  }

  @Test
  void purposeLabelsAreTheAdrsHkdfInfoStrings() {
    assertThat(Purpose.ACCESS_TOKEN.info()).isEqualTo("opaa:jwt:access-token");
    assertThat(Purpose.REFRESH_TOKEN_LOOKUP.info()).isEqualTo("opaa:jwt:refresh-token-lookup");
    assertThat(Purpose.ACTION_TOKEN_LOOKUP.info()).isEqualTo("opaa:jwt:action-token-lookup");
  }

  @Test
  void lookupHashIsAKeyedLowercaseHexSha256Digest() throws Exception {
    LocalAuthKeyService service = new LocalAuthKeyService(SECRET);

    String hash = service.lookupHash(Purpose.REFRESH_TOKEN_LOOKUP, "raw-refresh-token");

    assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(hash).isEqualTo(service.lookupHash(Purpose.REFRESH_TOKEN_LOOKUP, "raw-refresh-token"));
    assertThat(hash).isNotEqualTo(service.lookupHash(Purpose.ACTION_TOKEN_LOOKUP, "raw-refresh-token"));
    assertThat(hash).isNotEqualTo(service.lookupHash(Purpose.REFRESH_TOKEN_LOOKUP, "raw-refresh-tokeN"));
    assertThat(hash).isNotEqualTo(unkeyedSha256("raw-refresh-token"));
  }

  @Test
  void jtiHashIsThePlainSha256Digest() throws Exception {
    String jti = "0f5c9d3e-6a1b-4c2d-8e7f-000000000001";

    assertThat(LocalAuthKeyService.jtiHash(jti)).isEqualTo(unkeyedSha256(jti)).hasSize(64);
  }

  @Test
  void refusesToDeriveWithoutASecretAndNamesTheVariable() {
    LocalAuthKeyService service = new LocalAuthKeyService("   ");

    assertThatThrownBy(() -> service.key(Purpose.ACCESS_TOKEN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPAA_AUTH_JWT_SECRET")
        .hasMessageContaining("openssl rand -base64 48");
    assertThatThrownBy(() -> service.lookupHash(Purpose.ACTION_TOKEN_LOOKUP, "x"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPAA_AUTH_JWT_SECRET");
  }

  private static String unkeyedSha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
