package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The installation token is random, stored only as its hash and compared over the hashes. */
class InstallationTokensTest {

  @Test
  void aGeneratedTokenHas32AlphanumericCharactersAndIsNeverRepeated() {
    String first = InstallationTokens.generate();
    String second = InstallationTokens.generate();

    assertThat(first).hasSize(32).matches("[A-Za-z0-9]{32}");
    assertThat(second).isNotEqualTo(first);
  }

  @Test
  void theHashIsTheHexSha256AndNotTheToken() throws Exception {
    String expected =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest("geheim".getBytes(StandardCharsets.UTF_8)));

    assertThat(InstallationTokens.hash("geheim"))
        .isEqualTo(expected)
        .matches("[0-9a-f]{64}")
        .doesNotContain("geheim");
  }

  @Test
  void onlyTheTokenItselfMatchesItsHash() {
    String token = InstallationTokens.generate();
    String hash = InstallationTokens.hash(token);

    assertThat(InstallationTokens.matches(token, hash)).isTrue();
    for (String other : List.of(token + "x", token.substring(1), "", token.toLowerCase())) {
      if (!other.equals(token)) {
        assertThat(InstallationTokens.matches(other, hash)).as(other).isFalse();
      }
    }
    assertThat(InstallationTokens.matches(null, hash)).isFalse();
    assertThat(InstallationTokens.matches(token, null)).isFalse();
  }

  @Test
  void aConfiguredTokenMustBeStrong() {
    assertThatThrownBy(() -> new InstallationProperties("kurz", List.of(), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OPAA_INSTALLATION_TOKEN");
    assertThat(new InstallationProperties("  ", List.of(), " ", null).hasConfiguredToken())
        .isFalse();
    assertThat(new InstallationProperties("  ", List.of(), " ", null).hasAnswerFile()).isFalse();
  }

  @Test
  void aMistypedNetworkStopsTheStart() {
    assertThatThrownBy(() -> new InstallationProperties(null, List.of("10.0.0.0/33"), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OPAA_INSTALLATION_ALLOWED_CIDRS");
  }
}
