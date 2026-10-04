package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The target of a stored secret is origin plus the connector's binding: a secret follows only where
 * both stay, and the key the secret store binds it to agrees with that rule for every pair.
 */
class SecretTargetTest {

  private static final List<String> ADDRESSES =
      List.of(
          "https://files.example.com/a",
          "https://FILES.example.com/b",
          "https://files.example.com:443/c",
          "https://files.example.com:8443/a",
          "http://files.example.com/a",
          "http://files.example.com:80/a",
          "smb://fileserver/Daten",
          "smb://fileserver:445/Daten",
          "ftp://h/x",
          "ftp://h:80/x",
          " https://files.example.com/a",
          "https://my_internal_host/a",
          "relative/path");

  @Test
  void theKeyAgreesWithTheOriginRuleForEveryPair() {
    for (String first : ADDRESSES) {
      for (String second : ADDRESSES) {
        SecretTarget a = new SecretTarget(first, null);
        SecretTarget b = new SecretTarget(second, null);
        boolean sameKey = a.key() != null && Objects.equals(a.key(), b.key());
        assertThat(sameKey).as(first + " / " + second).isEqualTo(a.admits(b));
        assertThat(a.admits(b))
            .as(first + " / " + second)
            .isEqualTo(ServerAddress.sameOrigin(first, second));
      }
    }
  }

  @Test
  void aDifferentBindingOnTheSameOriginIsAnotherTarget() {
    SecretTarget stored = new SecretTarget("https://files.example.com/a", "fach@example.org");

    assertThat(stored.admits(new SecretTarget("https://files.example.com/b", "fach@example.org")))
        .isTrue();
    assertThat(stored.admits(new SecretTarget("https://files.example.com/a", "andere@example.org")))
        .isFalse();
    assertThat(stored.admits(new SecretTarget("https://files.example.com/a", null))).isFalse();
    assertThat(stored.key()).isEqualTo("https://files.example.com:443 fach@example.org");
  }
}
