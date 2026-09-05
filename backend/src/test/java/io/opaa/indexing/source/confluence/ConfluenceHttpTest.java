package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfluenceHttpTest {

  @Test
  void cqlAsksForChangedPagesInAWindowRelativeToTheInstanceClock() {
    // an absolute UTC timestamp would be read in the instance's own time zone; the
    // relative form lets the instance evaluate the window itself. N is rounded up so the window
    // never starts after the requested instant.
    Instant since = Instant.now().minus(Duration.ofMinutes(90)).minusSeconds(30);
    String cql = AbstractConfluenceClient.changedPagesCql(Set.of("HR", "ENG"), since);

    assertThat(cql)
        .startsWith("type=page AND space in (\"ENG\",\"HR\") AND lastmodified >= now(\"-")
        .endsWith("m\") ORDER BY lastmodified ASC");
    long minutes = Long.parseLong(cql.replaceAll(".*now\\(\"-(\\d+)m\"\\).*", "$1"));
    assertThat(minutes).isBetween(91L, 92L);
    assertThat(AbstractConfluenceClient.relativeWindow(Instant.now().plus(Duration.ofHours(2))))
        .startsWith("now(\"+");
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
  }
}
