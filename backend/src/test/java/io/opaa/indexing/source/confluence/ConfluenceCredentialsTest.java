package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.ConfluenceEdition;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ConfluenceCredentialsTest {

  @Test
  void cloudIsEmailAndTokenAsBasic() {
    ConfluenceCredentials credentials =
        ConfluenceCredentials.parse(ConfluenceEdition.CLOUD, "a@b.example:tok:with:colons");

    assertThat(credentials).isInstanceOf(ConfluenceCredentials.CloudApiToken.class);
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("a@b.example:tok:with:colons".getBytes(StandardCharsets.UTF_8));
    assertThat(credentials.authorizationHeader()).isEqualTo(expected);
    assertThat(credentials.edition()).isEqualTo(ConfluenceEdition.CLOUD);
  }

  @Test
  void dataCenterIsBareBearerToken() {
    ConfluenceCredentials credentials =
        ConfluenceCredentials.parse(ConfluenceEdition.DATA_CENTER, "  pat-value ");

    assertThat(credentials.authorizationHeader()).isEqualTo("Bearer pat-value");
    assertThat(credentials.edition()).isEqualTo(ConfluenceEdition.DATA_CENTER);
  }

  @Test
  void toStringNeverRevealsTheSecret() {
    assertThat(
            ConfluenceCredentials.parse(ConfluenceEdition.CLOUD, "a@b.example:geheim").toString())
        .doesNotContain("a@b.example")
        .doesNotContain("geheim");
    assertThat(ConfluenceCredentials.parse(ConfluenceEdition.DATA_CENTER, "geheim").toString())
        .doesNotContain("geheim");
  }

  @Test
  void rejectsBlankAndMisshapenValuesWithGermanMessages() {
    assertThatThrownBy(() -> ConfluenceCredentials.parse(ConfluenceEdition.CLOUD, " "))
        .isInstanceOf(ConfluenceCredentials.InvalidCredentialsFormatException.class)
        .hasMessageContaining("erforderlich");
    assertThatThrownBy(() -> ConfluenceCredentials.parse(ConfluenceEdition.CLOUD, "nur-token"))
        .isInstanceOf(ConfluenceCredentials.InvalidCredentialsFormatException.class)
        .hasMessageContaining("E-Mail");
    assertThatThrownBy(() -> ConfluenceCredentials.parse(ConfluenceEdition.CLOUD, "a@b.example:"))
        .isInstanceOf(ConfluenceCredentials.InvalidCredentialsFormatException.class);
    assertThatThrownBy(
            () -> ConfluenceCredentials.parse(ConfluenceEdition.DATA_CENTER, "a@b.example:token"))
        .isInstanceOf(ConfluenceCredentials.InvalidCredentialsFormatException.class)
        .hasMessageContaining("Cloud");
  }

  @Test
  void factoryRefusesCredentialsOfTheOtherEdition() {
    ConfluenceClientFactory factory =
        new ConfluenceClientFactory(
            ConfluenceProperties.defaults(),
            io.opaa.sourceaccess.TargetAddressValidator.disabled());
    ConfluenceConnection mismatch =
        new ConfluenceConnection(
            URI.create("https://wiki.example.org"),
            ConfluenceEdition.DATA_CENTER,
            new ConfluenceCredentials.CloudApiToken("a@b.example", "t"),
            null,
            -1,
            false);

    assertThatThrownBy(() -> factory.create(mismatch)).isInstanceOf(IllegalArgumentException.class);
  }
}
