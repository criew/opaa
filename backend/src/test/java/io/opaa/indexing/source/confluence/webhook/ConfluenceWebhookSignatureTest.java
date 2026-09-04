package io.opaa.indexing.source.confluence.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConfluenceWebhookSignatureTest {

  private static final byte[] BODY =
      "{\"event\":\"page_updated\",\"page\":{\"id\":\"102\"}}".getBytes(StandardCharsets.UTF_8);
  private static final String SECRET = "k7Q2mZ9x_geheim";

  @Test
  void acceptsADataCenterSignatureOverTheRawBody() {
    String header = ConfluenceWebhookSignature.sign(BODY, SECRET);
    assertThat(header).startsWith("sha256=").hasSize(7 + 64);
    assertThat(ConfluenceWebhookSignature.verify(BODY, header, null, SECRET)).isTrue();
    assertThat(ConfluenceWebhookSignature.verify(BODY, header.toUpperCase(), null, SECRET))
        .as("hex digits are case-insensitive, the prefix too")
        .isTrue();
  }

  @Test
  void rejectsASignatureOverADifferentBodyOrUnderADifferentSecret() {
    String header = ConfluenceWebhookSignature.sign(BODY, SECRET);
    byte[] tampered = "{\"page\":{\"id\":\"999\"}}".getBytes(StandardCharsets.UTF_8);
    assertThat(ConfluenceWebhookSignature.verify(tampered, header, null, SECRET)).isFalse();
    assertThat(ConfluenceWebhookSignature.verify(BODY, header, null, "anderes")).isFalse();
    assertThat(ConfluenceWebhookSignature.verify(BODY, "sha256=zz", null, SECRET))
        .as("unparseable hex")
        .isFalse();
    assertThat(ConfluenceWebhookSignature.verify(BODY, "sha1=abcd", null, SECRET))
        .as("other algorithm")
        .isFalse();
  }

  @Test
  void acceptsTheSharedSecretHeaderAnAutomationRuleSends() {
    assertThat(ConfluenceWebhookSignature.verify(BODY, null, SECRET, SECRET)).isTrue();
    assertThat(ConfluenceWebhookSignature.verify(BODY, null, SECRET + "x", SECRET)).isFalse();
    assertThat(ConfluenceWebhookSignature.verify(BODY, "sha256=00", SECRET, SECRET))
        .as("a bad signature does not veto a correct shared secret")
        .isTrue();
  }

  @Test
  void authenticatesNothingWithoutAStoredSecretOrWithoutAnyHeader() {
    assertThat(ConfluenceWebhookSignature.verify(BODY, null, null, SECRET)).isFalse();
    assertThat(ConfluenceWebhookSignature.verify(BODY, null, "", SECRET)).isFalse();
    String header = ConfluenceWebhookSignature.sign(BODY, SECRET);
    assertThat(ConfluenceWebhookSignature.verify(BODY, header, SECRET, null)).isFalse();
    assertThat(ConfluenceWebhookSignature.verify(BODY, header, SECRET, " ")).isFalse();
  }
}
