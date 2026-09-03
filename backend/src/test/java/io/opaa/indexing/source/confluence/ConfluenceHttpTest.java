package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfluenceHttpTest {

  @Test
  void parsesRetryAfterAsSecondsOrHttpDate() {
    assertThat(ConfluenceHttp.parseRetryAfter("7")).isEqualTo(Duration.ofSeconds(7));
    assertThat(ConfluenceHttp.parseRetryAfter(" 12 ")).isEqualTo(Duration.ofSeconds(12));

    String inTenSeconds =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.now().plusSeconds(10).atOffset(ZoneOffset.UTC));
    Duration parsed = ConfluenceHttp.parseRetryAfter(inTenSeconds);
    assertThat(parsed).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(11));

    assertThat(ConfluenceHttp.parseRetryAfter("bald")).isNull();
  }

  @Test
  void cqlAsksForIdentifiersOfChangedPagesInUtcMinutes() {
    String cql =
        AbstractConfluenceClient.changedPagesCql(
            Set.of("HR", "ENG"), Instant.parse("2026-09-01T12:34:56Z"));

    assertThat(cql)
        .isEqualTo(
            "type=page AND space in (\"ENG\",\"HR\") AND lastmodified >= \"2026-09-01 12:34\""
                + " ORDER BY lastmodified ASC");
    assertThat(cql).doesNotContain("expand").doesNotContain("body");
  }

  @Test
  void propertiesFallBackToDefaults() {
    ConfluenceProperties defaults = ConfluenceProperties.defaults();

    assertThat(defaults.pageSize()).isEqualTo(100);
    assertThat(defaults.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(defaults.maxRateLimitRetries()).isEqualTo(6);
    assertThat(defaults.maxRetryAfter()).isEqualTo(Duration.ofMinutes(2));
    assertThat(defaults.maxResponseBytes()).isEqualTo(10_485_760L);
    assertThat(defaults.maxAttachmentSizeBytes()).isEqualTo(20_971_520L);
    assertThat(defaults.userAgent()).isEqualTo("OPAA-Indexer/1.0");
  }
}
