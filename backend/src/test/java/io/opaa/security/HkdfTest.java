package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * {@link Hkdf} against the RFC 5869 test vectors (Appendix A, SHA-256) and its derivation contract.
 */
class HkdfTest {

  private static final HexFormat HEX = HexFormat.of();

  @Test
  void matchesRfc5869TestCase1() {
    byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
    byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
    byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");

    assertThat(HEX.formatHex(Hkdf.extract(salt, ikm)))
        .isEqualTo("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
    assertThat(HEX.formatHex(Hkdf.deriveKey(ikm, salt, info, 42)))
        .isEqualTo(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
  }

  @Test
  void matchesRfc5869TestCase2WithLongerInputsAndMultiBlockOutput() {
    byte[] ikm = range(0x00, 0x4f);
    byte[] salt = range(0x60, 0xaf);
    byte[] info = range(0xb0, 0xff);

    assertThat(HEX.formatHex(Hkdf.extract(salt, ikm)))
        .isEqualTo("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244");
    assertThat(HEX.formatHex(Hkdf.deriveKey(ikm, salt, info, 82)))
        .isEqualTo(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87");
  }

  @Test
  void matchesRfc5869TestCase3WithoutSaltAndInfo() {
    byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");

    assertThat(HEX.formatHex(Hkdf.extract(new byte[0], ikm)))
        .isEqualTo("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04");
    assertThat(HEX.formatHex(Hkdf.deriveKey(ikm, null, null, 42)))
        .isEqualTo(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8");
  }

  @Test
  void isDeterministicAndDomainSeparatedByInfo() {
    byte[] ikm = "input-keying-material".getBytes(StandardCharsets.UTF_8);
    byte[] purposeA = "opaa:jwt:a".getBytes(StandardCharsets.UTF_8);
    byte[] purposeB = "opaa:jwt:b".getBytes(StandardCharsets.UTF_8);

    byte[] first = Hkdf.deriveKey(ikm, null, purposeA, 32);

    assertThat(first).hasSize(32).isEqualTo(Hkdf.deriveKey(ikm, null, purposeA, 32));
    assertThat(first).isNotEqualTo(Hkdf.deriveKey(ikm, null, purposeB, 32));
  }

  @Test
  void refusesOutputLongerThanTheRfcAllows() {
    byte[] prk = new byte[32];

    assertThatThrownBy(() -> Hkdf.expand(prk, null, 255 * 32 + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Hkdf.expand(prk, null, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static byte[] range(int firstInclusive, int lastInclusive) {
    byte[] bytes = new byte[lastInclusive - firstInclusive + 1];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (firstInclusive + i);
    }
    return bytes;
  }
}
