package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RssFeedParser} entirely against checked-in fixtures under {@code
 * src/test/resources/rss-feeds/} (#466) - no network, no database, as ADR-0017 and the issue's
 * motivation call for. Fixtures are invented and generic (example.invalid domains, made-up
 * agency/titles) - never a reference to a real feed or institution.
 */
class RssFeedParserTest {

  private final RssFeedParser parser = new RssFeedParser();

  @Test
  void parsesTheUsualFeedIntoItsEntries() {
    List<RssFeedEntry> entries = parseFixture("usual-feed.xml");

    assertThat(entries).hasSize(2);

    RssFeedEntry first = entries.get(0);
    assertThat(first.title()).isEqualTo("Neue Formularvorlage veröffentlicht");
    assertThat(first.link()).isEqualTo("https://feeds.example.invalid/mitteilungen/artikel-1");
    assertThat(first.description())
        .isEqualTo("Die Beispielbehörde hat eine neue Formularvorlage veröffentlicht.");
    assertThat(first.publishedAt()).contains(Instant.parse("2024-01-01T10:00:00Z"));

    RssFeedEntry second = entries.get(1);
    assertThat(second.link()).isEqualTo("https://feeds.example.invalid/mitteilungen/artikel-2");
    assertThat(second.publishedAt()).contains(Instant.parse("2024-01-02T08:30:00Z"));
  }

  @Test
  void emptyFeedYieldsNoEntries() {
    assertThat(parseFixture("empty-feed.xml")).isEmpty();
  }

  @Test
  void entryWithoutDateKeepsTheEntryWithAnEmptyPublishedAt() {
    List<RssFeedEntry> entries = parseFixture("entry-without-date.xml");

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).publishedAt()).isEmpty();
    assertThat(entries.get(0).link())
        .isEqualTo("https://feeds.example.invalid/mitteilungen/artikel-ohne-datum");
  }

  @Test
  void entryWithoutLinkIsSkippedButOtherEntriesSurvive() {
    List<RssFeedEntry> entries = parseFixture("entry-without-link.xml");

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).link())
        .isEqualTo("https://feeds.example.invalid/mitteilungen/artikel-mit-verweis");
  }

  @Test
  void alternateDateFormatWithoutWeekdayAndWithZoneAbbreviationIsStillParsed() {
    List<RssFeedEntry> entries = parseFixture("alternate-date-format.xml");

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).publishedAt()).contains(Instant.parse("2024-01-01T10:00:00Z"));
  }

  @Test
  void foreignNamespaceElementsAreIgnored() {
    List<RssFeedEntry> entries = parseFixture("foreign-namespaces.xml");

    assertThat(entries).hasSize(1);
    RssFeedEntry entry = entries.get(0);
    assertThat(entry.title()).isEqualTo("Mitteilung mit fremden Namensräumen");
    assertThat(entry.link())
        .isEqualTo("https://feeds.example.invalid/mitteilungen/artikel-namensraeume");
    assertThat(entry.description())
        .isEqualTo("Der Eintrag trägt zusätzliche Elemente aus fremden Namensräumen.");
  }

  @Test
  void enclosureElementIsIgnoredButTheEntryIsStillRead() {
    List<RssFeedEntry> entries = parseFixture("feed-with-enclosure.xml");

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).link())
        .isEqualTo("https://feeds.example.invalid/mitteilungen/artikel-anhang");
  }

  @Test
  void externalEntityIsNotResolved() {
    // If SUPPORT_DTD/IS_SUPPORTING_EXTERNAL_ENTITIES were not disabled, the JDK's StAX
    // implementation would attempt to read the referenced (nonexistent) file and its resulting
    // exception message would name that path - proving the attempt happened. Asserting that
    // does not appear proves the entity was never resolved, regardless of whether the parser
    // rejects the DOCTYPE outright (mapped to RssFeedParseException) or simply never substitutes
    // the entity.
    InputStream input = fixture("external-entity.xml");

    assertThatThrownBy(() -> parser.parse(input))
        .isInstanceOfAny(RssFeedParseException.class)
        .satisfies(
            e -> {
              assertThat(rootCauseMessage(e)).doesNotContain("this-path-must-never-be-read.txt");
            });
  }

  @Test
  void nonXmlInputFailsWithAGermanUserFacingMessage() {
    InputStream input = new ByteArrayInputStream("kein xml".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> parser.parse(input))
        .isInstanceOf(RssFeedParseException.class)
        .hasMessageContaining("RSS-Feed")
        .hasMessageContaining("kein gültiges XML");
  }

  private static String rootCauseMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return String.valueOf(current.getMessage());
  }

  private List<RssFeedEntry> parseFixture(String fileName) {
    return parser.parse(fixture(fileName));
  }

  private InputStream fixture(String fileName) {
    InputStream stream = getClass().getClassLoader().getResourceAsStream("rss-feeds/" + fileName);
    if (stream == null) {
      throw new IllegalStateException("Missing test fixture: rss-feeds/" + fileName);
    }
    return stream;
  }
}
