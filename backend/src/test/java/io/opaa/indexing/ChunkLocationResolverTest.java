package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** #667: the Fundort derived from headings and page breaks. */
class ChunkLocationResolverTest {

  @Test
  void flatTextYieldsNoLocation() {
    var resolver = ChunkLocationResolver.forText("Just a paragraph. And another one.");

    assertThat(resolver.locate(0, 20)).isNull();
  }

  @Test
  void reportsTheHeadingPathInEffectAtTheChunkStart() {
    String text =
        """
        # Dienstanweisung
        Einleitung.
        ## 4 Fristen
        Allgemeines.
        ### 4.2 Fristsetzung
        Die Frist beträgt zwei Wochen.
        ## 5 Zuständigkeit
        Das Referat.
        """;
    var resolver = ChunkLocationResolver.forText(text);

    int fristsetzung = text.indexOf("Die Frist");
    int zustaendigkeit = text.indexOf("Das Referat");
    assertThat(resolver.locate(fristsetzung, fristsetzung + 10))
        .isEqualTo("Abschn. 4 Fristen › 4.2 Fristsetzung");
    assertThat(resolver.locate(zustaendigkeit, zustaendigkeit + 5))
        .isEqualTo("Abschn. Dienstanweisung › 5 Zuständigkeit");
    assertThat(resolver.locate(text.indexOf("Einleitung"), text.indexOf("Einleitung") + 3))
        .isEqualTo("Abschn. Dienstanweisung");
  }

  @Test
  void textBeforeTheFirstHeadingHasNoHeadingPath() {
    String text = "Vorspann ohne Überschrift.\n# Erste Überschrift\nInhalt.";
    var resolver = ChunkLocationResolver.forText(text);

    assertThat(resolver.locate(0, 8)).isNull();
  }

  @Test
  void reportsThePageRangeFromFormFeeds() {
    String text = "Seite eins.\fSeite zwei.\fSeite drei.";
    var resolver = ChunkLocationResolver.forText(text);

    assertThat(resolver.locate(0, 5)).isEqualTo("S. 1");
    assertThat(resolver.locate(text.indexOf("zwei"), text.indexOf("zwei") + 3)).isEqualTo("S. 2");
    assertThat(resolver.locate(text.indexOf("eins"), text.length())).isEqualTo("S. 1–3");
  }

  @Test
  void combinesPageAndHeading() {
    String text = "# Kapitel 1\nText.\f## Abschnitt 1.1\nMehr Text.";
    var resolver = ChunkLocationResolver.forText(text);

    int mehr = text.indexOf("Mehr");
    assertThat(resolver.locate(mehr, mehr + 4))
        .isEqualTo("S. 2 · Abschn. Kapitel 1 › Abschnitt 1.1");
  }

  @Test
  void ignoresHashesThatAreNotHeadings() {
    String text = "Ticket #123 und ####kein Titel\nText.";
    var resolver = ChunkLocationResolver.forText(text);

    assertThat(resolver.locate(text.indexOf("Text"), text.length())).isNull();
  }
}
