package io.opaa.indexing.source;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookSignature;
import io.opaa.indexing.source.s3.events.S3EventAuthentication;
import io.opaa.test.SourceTypes;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Every push intake does its own check against a stand-in for a library it does not serve - the
 * work that keeps the shared intake's answer time independent of the library and its connector.
 */
class PushIntakeStandInTest {

  private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

  private final SourceConnectorRegistry registry = TestSourceConnectors.connectors().registry();

  @Test
  void confluenceChecksTheSignatureAgainstAStandIn() {
    UnaryOperator<String> header =
        Map.of(
                ConfluenceWebhookSignature.HUB_SIGNATURE_HEADER, "sha256=abcd",
                ConfluenceWebhookSignature.SHARED_SECRET_HEADER, "geheim")
            ::get;
    try (MockedStatic<ConfluenceWebhookSignature> signature =
        mockStatic(ConfluenceWebhookSignature.class, CALLS_REAL_METHODS)) {
      registry.pushIntakeHandler(SourceTypes.CONFLUENCE).orElseThrow().rejectForeign(BODY, header);

      signature.verify(
          () -> ConfluenceWebhookSignature.verify(BODY, "sha256=abcd", "geheim", null));
    }
  }

  @Test
  void s3ChecksTheTokenAgainstAStandIn() {
    UnaryOperator<String> header =
        Map.of(
                "Authorization",
                "Bearer token",
                S3EventAuthentication.SHARED_SECRET_HEADER,
                "geheim")
            ::get;
    try (MockedStatic<S3EventAuthentication> authentication =
        mockStatic(S3EventAuthentication.class, CALLS_REAL_METHODS)) {
      registry.pushIntakeHandler(SourceTypes.S3).orElseThrow().rejectForeign(BODY, header);

      authentication.verify(() -> S3EventAuthentication.verify("Bearer token", "geheim", null));
    }
  }
}
