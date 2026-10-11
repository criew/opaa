package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class S3ChangeMarkerTest {

  private static final Instant MODIFIED = Instant.parse("2026-10-11T08:00:00Z");

  @Test
  void anETagIsTheFeatureWithItsSize() {
    assertThat(S3ChangeMarker.of("d41d8cd98f00b204e9800998ecf8427e", 6, MODIFIED))
        .isEqualTo("e:d41d8cd98f00b204e9800998ecf8427e|6");
  }

  /**
   * A store with its own ETag form (a gateway prefix) keeps the ETag as the feature: a re-upload of
   * the same content must not count as a change (ADR-0027, Entscheidung 4).
   */
  @Test
  void aLongETagStaysTheFeatureInsteadOfFallingBackToTheTimestamp() {
    String eTag = "gateway-replica-eu-central-1/" + "0123456789abcdef".repeat(4) + "-17";

    String marker = S3ChangeMarker.of(eTag, 1_048_576, MODIFIED);

    assertThat(marker).isEqualTo("e:" + eTag + "|1048576").hasSizeGreaterThan(64);
  }

  @Test
  void withoutAnETagTheTimestampStandsIn() {
    assertThat(S3ChangeMarker.of(null, 6, MODIFIED))
        .isEqualTo("t:" + MODIFIED.toEpochMilli() + "|6");
    assertThat(S3ChangeMarker.of(" ", 6, null)).isNull();
  }
}
