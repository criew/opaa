package io.opaa.indexing.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.document.SourceDocumentContext;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;

/**
 * {@link ChunkContextPrefix} (#1072): the format of the Kontextpräfix, that an empty segment is
 * left out entirely, and which Fundort counts as Strukturkontext.
 */
class ChunkContextPrefixTest {

  @Test
  void joinsTitleMetadataAndStructureContextInTheOrderOfTheSpecification() {
    String prefix =
        ChunkContextPrefix.build(
            "Verwaltungsgebührensatzung",
            List.of("Fassung 2026", "Kommune"),
            "§ 7 Gebühren für Personaldokumente");

    assertThat(prefix)
        .isEqualTo(
            "Verwaltungsgebührensatzung › Fassung 2026 › Kommune › §"
                + " 7 Gebühren für Personaldokumente");
  }

  @Test
  void leavesOutEverySegmentThatCarriesNothingAndYieldsNullWithoutAnySegment() {
    assertThat(ChunkContextPrefix.build("Satzung", Arrays.asList(null, "  ", "Fassung 2026"), null))
        .isEqualTo("Satzung › Fassung 2026");
    assertThat(ChunkContextPrefix.build(null, List.of(), "  ")).isNull();
    assertThat(ChunkContextPrefix.build(null, List.of(), "§ 7")).isEqualTo("§ 7");
  }

  @Test
  void takesTheStructureContextOnlyFromASectionFundortTheChunkDoesNotAlreadyOpenWith() {
    assertThat(
            ChunkContextPrefix.structureContextFrom("Satzung", "Abschn. § 7 Gebühren", "37,00 EUR"))
        .isEqualTo("§ 7 Gebühren");
    assertThat(ChunkContextPrefix.structureContextFrom("Satzung", "S. 2–4", "37,00 EUR"))
        .as("a page number names no content")
        .isNull();
    assertThat(
            ChunkContextPrefix.structureContextFrom(
                "Satzung", "Abschn. § 7 Gebühren", "§ 7 Gebühren\n\n37,00 EUR"))
        .as("a pipeline that cuts on headings keeps them in the text; repeating adds nothing")
        .isNull();
    assertThat(ChunkContextPrefix.structureContextFrom("Satzung", null, "37,00 EUR")).isNull();
    assertThat(ChunkContextPrefix.structureContextFrom("Satzung", 42, "37,00 EUR")).isNull();
  }

  // regression guard for #1308: a Markdown H1 equal to the title must not repeat it in the prefix
  @Test
  void dropsALeadingHeadingThatRepeatsTheTitleFromTheStructureContext() {
    assertThat(
            ChunkContextPrefix.forChunk(
                true,
                true,
                "Verwaltungsgebührensatzung",
                List.of(),
                "Abschn. Verwaltungsgebührensatzung › § 7 Gebühren",
                "37,00 EUR"))
        .isEqualTo("Verwaltungsgebührensatzung › § 7 Gebühren");
    assertThat(
            ChunkContextPrefix.forChunk(
                true,
                true,
                "Verwaltungsgebührensatzung",
                List.of("Fassung 2026"),
                "Abschn.  verwaltungsGEBÜHRENsatzung  › § 7 Gebühren",
                "37,00 EUR"))
        .as("the title comparison ignores case and surrounding or repeated whitespace")
        .isEqualTo("Verwaltungsgebührensatzung › Fassung 2026 › § 7 Gebühren");
    assertThat(
            ChunkContextPrefix.forChunk(
                true,
                true,
                "Verwaltungsgebührensatzung",
                List.of(),
                "Abschn. Verwaltungsgebührensatzung",
                "Satzung der Stadt Kalkstadt"))
        .as("a path that is nothing but the title leaves no Strukturkontext")
        .isEqualTo("Verwaltungsgebührensatzung");
    assertThat(
            ChunkContextPrefix.forChunk(
                true,
                true,
                "Verwaltungsgebührensatzung",
                List.of(),
                "Abschn. Gebührenordnung › § 7 Gebühren",
                "37,00 EUR"))
        .as("a heading that differs from the title stays")
        .isEqualTo("Verwaltungsgebührensatzung › Gebührenordnung › § 7 Gebühren");
  }

  // regression guard for #1308: the repetition check must see through the Markdown heading marker
  @Test
  void dropsTheStructureContextWhenTheChunkOpensWithItAsAMarkdownHeading() {
    assertThat(
            ChunkContextPrefix.forChunk(
                true,
                true,
                "Satzung",
                List.of(),
                "Abschn. § 7 Gebühren",
                "   ### § 7 Gebühren\n\n37,00 EUR"))
        .isEqualTo("Satzung");
    assertThat(
            ChunkContextPrefix.forChunk(
                true,
                true,
                "Verwaltungsgebührensatzung",
                List.of(),
                "Abschn. Verwaltungsgebührensatzung › § 7 Gebühren",
                "## § 7 Gebühren\n\n37,00 EUR"))
        .isEqualTo("Verwaltungsgebührensatzung");
  }

  @Test
  void keepsNoStructureContextWhenTheChunkOpensWithTheWholeHeadingPathIncludingTheTitle() {
    assertThat(
            ChunkContextPrefix.forChunk(
                true,
                true,
                "Verwaltungsgebührensatzung",
                List.of(),
                "Abschn. Verwaltungsgebührensatzung › § 7 Gebühren",
                "Verwaltungsgebührensatzung › § 7 Gebühren\n\n37,00 EUR"))
        .as("the heading-section pipelines open every chunk with its full heading path")
        .isEqualTo("Verwaltungsgebührensatzung");
  }

  @Test
  void dropsTheStructureContextWhenTheChunkOpensWithSeveralMarkdownHeadingLinesSpellingIt() {
    assertThat(
            ChunkContextPrefix.forChunk(
                true,
                true,
                "Satzung",
                List.of(),
                "Abschn. Gebührenordnung › § 7 Gebühren",
                "# Gebührenordnung\n\n## § 7 Gebühren\n\n37,00 EUR"))
        .isEqualTo("Satzung");
    assertThat(
            ChunkContextPrefix.forChunk(
                true,
                true,
                "Gebührenordnung",
                List.of(),
                "Abschn. Gebührenordnung › § 7 Gebühren",
                "# Gebührenordnung\n\n## § 7 Gebühren\n\n37,00 EUR"))
        .isEqualTo("Gebührenordnung");
    assertThat(
            ChunkContextPrefix.forChunk(
                true,
                true,
                "Satzung",
                List.of(),
                "Abschn. Gebührenordnung › § 7 Gebühren",
                "# Gebührenordnung\n\n## § 8 Fälligkeit\n\n37,00 EUR"))
        .as("heading lines naming a different section keep the context")
        .isEqualTo("Satzung › Gebührenordnung › § 7 Gebühren");
  }

  // The stamp decides which documents the Nachlauf re-embeds; changing its input spelling would
  // select every document at once. The expected values are SHA-256 over NUL-joined segments.
  @Test
  void keepsTheStampOfTitleAndValuesStable() {
    assertThat(ChunkContextPrefix.stampOf("Satzung", List.of("Fassung 2026", "Kommune")))
        .isEqualTo("4b19fe3a56d3562766dc8c7527910c27");
    assertThat(ChunkContextPrefix.stampOf(null, null))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb924");
  }

  @Test
  void formatsThePrefixInBracketsAheadOfTheUntouchedChunkText() {
    assertThat(ChunkContextPrefix.format("Satzung › Fassung 2026", "37,00 EUR"))
        .isEqualTo("[Satzung › Fassung 2026]\n\n37,00 EUR");
  }

  @Test
  void appliesThePrefixToTheEmbeddingInputOnlyAndReadsTheStructureContextFromTheFundort() {
    Document chunk =
        new Document(
            "37,00 EUR",
            Map.of(
                ChunkingService.LOCATION_METADATA_KEY, "Abschn. § 7 Gebühren", "file_name", "x"));

    ChunkContextPrefix.applyTo(chunk, true, true, "Satzung", List.of("Fassung 2026"));

    assertThat(chunk.getFormattedContent(MetadataMode.EMBED))
        .isEqualTo("[Satzung › Fassung 2026 › § 7 Gebühren]\n\n37,00 EUR");
    assertThat(chunk.getText()).isEqualTo("37,00 EUR");
  }

  @Test
  void derivesTheIngestTitleFromTheFileNameOrTheDeclaredTitleBehindItsHierarchyPath() {
    assertThat(ChunkContextPrefix.ingestTitle(false, "001_personalausweis.md", "egal", null))
        .isEqualTo("personalausweis");
    assertThat(
            ChunkContextPrefix.ingestTitle(
                true, "x", "Öffnungszeiten", new SourceDocumentContext("K", "Bürgerservice")))
        .isEqualTo("Bürgerservice / Öffnungszeiten");
    assertThat(
            ChunkContextPrefix.ingestTitle(
                true, "x", "Öffnungszeiten", new SourceDocumentContext("K", " ")))
        .isEqualTo("Öffnungszeiten");
    assertThat(ChunkContextPrefix.ingestTitle(true, "https://example.org/a", null, null)).isNull();
    assertThat(ChunkContextPrefix.eligible(null)).isFalse();
    assertThat(ChunkContextPrefix.eligible("Öffnungszeiten")).isTrue();
  }

  @Test
  void countsADocumentAsSplitFromTwoChunksOn() {
    assertThat(ChunkContextPrefix.documentWasSplit(1)).isFalse();
    assertThat(ChunkContextPrefix.documentWasSplit(2)).isTrue();
  }

  @Test
  void leavesTheEmbeddingInputByteIdenticalToTheTextWhenTheChunkGetsNoPrefix() {
    Document unsplit = new Document("37,00 EUR", Map.of("file_name", "satzung.md"));
    ChunkContextPrefix.applyTo(unsplit, true, false, "Satzung", List.of());
    assertThat(unsplit.getFormattedContent(MetadataMode.EMBED)).isEqualTo("37,00 EUR");

    Document ineligible = new Document("37,00 EUR", Map.of("file_name", "satzung.md"));
    ChunkContextPrefix.applyTo(ineligible, false, true, "Satzung", List.of("Fassung 2026"));
    assertThat(ineligible.getFormattedContent(MetadataMode.EMBED)).isEqualTo("37,00 EUR");
  }
}
