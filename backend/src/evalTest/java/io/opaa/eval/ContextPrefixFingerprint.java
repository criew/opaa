package io.opaa.eval;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.chunk.ChunkContextPrefix;
import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.SourceDocumentContext;
import io.opaa.indexing.format.DocumentProperties;
import io.opaa.indexing.metadata.CoreContextPrefixSettings;
import io.opaa.indexing.metadata.CoreMetadata;
import io.opaa.indexing.metadata.CoreMetadataExtractor;
import io.opaa.indexing.metadata.DocumentTypeVocabulary;
import io.opaa.indexing.metadata.DocumentTypeVocabularyEntry;
import io.opaa.indexing.metadata.ExtractedCoreMetadata;
import io.opaa.indexing.metadata.ExtractedDate;
import io.opaa.library.KnowledgeLibrary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;

/**
 * The fixed point recording the form of the Kontextpräfix a corpus was embedded with: SHA-256 over
 * the embedding inputs production computes for fixed sample documents. Each sample runs through the
 * production decisions - the source's declared properties, the deterministic Kernfeld extraction,
 * ingest title and eligibility, the single-chunk rule, the core-field Wirkstellen of a library as
 * its factory creates it - and {@link ChunkContextPrefix#applyTo}. What stays outside is named in
 * ADR-0012, Entscheidung 57.
 */
final class ContextPrefixFingerprint {

  /**
   * One document as the ingest sees it, reduced to one chunk.
   *
   * @param source a file name, or the address of a text source (then {@code declaredTitle} and
   *     {@code context} are what that source declares)
   * @param parsed the properties the format pipeline read
   * @param coreFieldsInPrefix whether the library has Dokumentart and Datum switched into the
   *     prefix; {@code false} keeps the factory defaults the evaluation libraries are created with
   * @param extractionFailed the ingest's path when the core-field extraction throws
   */
  record Sample(
      String source,
      boolean textSource,
      String declaredTitle,
      SourceDocumentContext context,
      DocumentProperties parsed,
      boolean coreFieldsInPrefix,
      boolean extractionFailed,
      int chunkCount,
      String location,
      String chunkText) {}

  static final DocumentTypeVocabulary VOCABULARY =
      DocumentTypeVocabulary.of(
          List.of(new DocumentTypeVocabularyEntry("MERKBLATT", "Merkblatt", 1, Set.of())));

  /**
   * One sample per branch: a quoted frontmatter title outranking the first heading, a first heading
   * only, no title source (file-name fallback), a chunk opening with its headings, core values on a
   * one-chunk and on a split document, a one-chunk document without values, a text source's
   * declared title, a failed extraction falling back to the hierarchy path, and a text source
   * without a title.
   */
  static final List<Sample> SAMPLES =
      List.of(
          file(
              "001_verwaltungsgebuehrensatzung.md",
              markdown(
                  Map.of("titel", "\"Verwaltungsgebührensatzung\""),
                  "Satzung über Verwaltungsgebühren"),
              false,
              4,
              "Abschn. Verwaltungsgebührensatzung › § 7 Gebühren",
              "Personalausweis: 37,00 EUR"),
          file(
              "satzung.md",
              markdown(Map.of(), "Satzung"),
              false,
              2,
              "Abschn.  SATZUNG › Gebührenordnung › § 8 Fälligkeit",
              "Die Gebühr wird mit der Antragstellung fällig."),
          file(
              "city-0022_prag_altstadt-rundgang.md",
              markdown(Map.of(), null),
              false,
              3,
              "S. 2–4",
              "Die Karlsbrücke verbindet die Altstadt mit der Kleinseite."),
          file(
              "gebuehrenordnung.md",
              markdown(Map.of("titel", "Satzung"), "Gebührenordnung"),
              false,
              3,
              "Abschn. Gebührenordnung › § 7 Gebühren",
              "# Gebührenordnung\n\n## § 7 Gebühren\n\n37,00 EUR"),
          file(
              "merkblatt-wohnsitz.md",
              markdown(
                  Map.of(
                      "titel", "Merkblatt Wohnsitz",
                      "dokumentart", "Merkblatt",
                      "stand_datum", "2026-03-12"),
                  null),
              true,
              1,
              null,
              "Die Anmeldung erfolgt binnen zwei Wochen."),
          file(
              "merkblatt-wohnsitz.md",
              markdown(
                  Map.of(
                      "titel", "Merkblatt Wohnsitz", "dokumentart", "Merkblatt", "fassung", "2025"),
                  null),
              true,
              2,
              "Abschn. Fristen",
              "Die Anmeldung erfolgt binnen zwei Wochen."),
          file(
              "merkblatt-wohnsitz.md",
              markdown(Map.of("titel", "Merkblatt Wohnsitz", "dokumentart", "Merkblatt"), null),
              false,
              1,
              null,
              "Die Anmeldung erfolgt binnen zwei Wochen."),
          new Sample(
              "https://wiki.example.org/pages/42",
              true,
              "Öffnungszeiten",
              new SourceDocumentContext("BUERGER", "Bürgerservice / Rathaus"),
              DocumentProperties.EMPTY,
              false,
              false,
              2,
              "Abschn. Öffnungszeiten › Samstag",
              "Von 9 bis 12 Uhr."),
          new Sample(
              "https://wiki.example.org/pages/42",
              true,
              "Öffnungszeiten",
              new SourceDocumentContext("BUERGER", "Bürgerservice / Rathaus"),
              DocumentProperties.EMPTY,
              false,
              true,
              2,
              "Abschn. Sonntag",
              "Geschlossen."),
          new Sample(
              "https://example.org/feed/eintrag-1",
              true,
              null,
              SourceDocumentContext.NONE,
              DocumentProperties.EMPTY,
              false,
              false,
              2,
              "Abschn. Meldung",
              "Rathaus am Montag geschlossen."));

  private static final UUID ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000002");

  private ContextPrefixFingerprint() {}

  /** The fingerprint of the prefix form this build embeds with. */
  static String current() {
    return of(ContextPrefixFingerprint::productionEmbeddingInput);
  }

  /** SHA-256 hex over each sample's embedding input, NUL-separated, in {@link #SAMPLES} order. */
  static String of(Function<Sample, String> embeddingInput) {
    StringBuilder source = new StringBuilder();
    for (Sample sample : SAMPLES) {
      source.append(embeddingInput.apply(sample)).append('\0');
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(source.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", e);
    }
  }

  static String productionEmbeddingInput(Sample sample) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(ORGANIZATION, "Eval-Zielbibliothek", null, OWNER, false);
    if (sample.coreFieldsInPrefix()) {
      library.applyCoreContextPrefix(true, true);
    }
    DocumentIngest ingest = ingestOf(sample, library);
    CoreMetadata core =
        sample.extractionFailed()
            ? CoreMetadata.EMPTY
            : coreMetadata(ingest.fileName(), ingest.declaredOver(sample.parsed()));
    String ingestTitle =
        ChunkContextPrefix.ingestTitle(
            ingest.syntheticName(), ingest.fileName(), ingest.title(), ingest.context());
    boolean eligible = ChunkContextPrefix.eligible(ingestTitle);
    List<String> values = CoreContextPrefixSettings.of(library).coreValues(core);

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("file_name", ingest.fileName());
    if (sample.location() != null) {
      metadata.put(ChunkingService.LOCATION_METADATA_KEY, sample.location());
    }
    Document chunk = new Document(sample.chunkText(), metadata);
    ChunkContextPrefix.applyTo(
        chunk,
        eligible,
        ChunkContextPrefix.documentWasSplit(sample.chunkCount()),
        ChunkContextPrefix.titleAtRest(eligible, core.title(), ingestTitle),
        values);
    return chunk.getFormattedContent(MetadataMode.EMBED);
  }

  private static DocumentIngest ingestOf(Sample sample, KnowledgeLibrary library) {
    if (sample.textSource()) {
      return DocumentIngest.text(library, sample.source(), sample.chunkText())
          .title(sample.declaredTitle())
          .context(sample.context())
          .sourceType(DocumentSourceType.CONFLUENCE)
          .build();
    }
    return DocumentIngest.builder(library)
        .file(Path.of(sample.source()), 0)
        .filePath("/eval/" + sample.source())
        .fileName(sample.source())
        .sourceType(DocumentSourceType.FILESYSTEM)
        .build();
  }

  /** The deterministic extraction's result, as the stored values read it back. */
  private static CoreMetadata coreMetadata(String fileName, DocumentProperties properties) {
    ExtractedCoreMetadata extracted =
        CoreMetadataExtractor.extract(fileName, properties, VOCABULARY);
    String code = extracted.documentTypeCode().orElse(null);
    ExtractedDate date = extracted.date().orElse(null);
    return new CoreMetadata(
        extracted.title().orElse(null),
        null,
        code,
        code == null ? null : VOCABULARY.labelOf(code).orElse(code),
        null,
        date == null ? null : date.date(),
        date == null ? null : date.precision(),
        null);
  }

  private static Sample file(
      String fileName,
      DocumentProperties parsed,
      boolean coreFieldsInPrefix,
      int chunkCount,
      String location,
      String chunkText) {
    return new Sample(
        fileName,
        false,
        null,
        null,
        parsed,
        coreFieldsInPrefix,
        false,
        chunkCount,
        location,
        chunkText);
  }

  private static DocumentProperties markdown(Map<String, String> frontmatter, String firstHeading) {
    return new DocumentProperties(
        null, null, null, null, firstHeading, null, null, ".md", false, frontmatter, Map.of());
  }
}
