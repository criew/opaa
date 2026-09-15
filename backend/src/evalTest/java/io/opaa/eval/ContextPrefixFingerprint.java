package io.opaa.eval;

import io.opaa.indexing.chunk.ChunkContextPrefix;
import io.opaa.indexing.chunk.ChunkContextTitle;
import io.opaa.indexing.chunk.ChunkingService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;

/**
 * The fixed point recording the form of the Kontextpräfix a corpus was embedded with: SHA-256 over
 * the embedding inputs production computes for a fixed set of sample chunks, run through {@link
 * ChunkContextPrefix#applyTo} and {@link ChunkContextTitle#deriveTitle} exactly as both writing
 * paths do. Any change to the prefix's segments, their order, the Strukturkontext rule, the title
 * fallback or the bracket format moves this value without a version to bump by hand.
 */
final class ContextPrefixFingerprint {

  /** One chunk as the ingest sees it; {@code coreTitle} may be null to exercise the fallback. */
  record Sample(
      boolean eligible,
      boolean documentWasSplit,
      String coreTitle,
      String fileName,
      List<String> values,
      String location,
      String chunkText) {}

  /**
   * One sample per branch that shapes the input: title and values, a title-repeating heading path,
   * a chunk opening with its heading, the file-name fallback, a one-chunk document with and without
   * values, and a document type that never gets a prefix.
   */
  static final List<Sample> SAMPLES =
      List.of(
          new Sample(
              true,
              true,
              "Verwaltungsgebührensatzung",
              "001_verwaltungsgebuehrensatzung.md",
              List.of("Fassung 2026", "Kommune"),
              "Abschn. Verwaltungsgebührensatzung › § 7 Gebühren",
              "Personalausweis: 37,00 EUR"),
          new Sample(
              true,
              true,
              "Satzung",
              "satzung.md",
              List.of(),
              "Abschn.  SATZUNG › Gebührenordnung › § 8 Fälligkeit",
              "Die Gebühr wird mit der Antragstellung fällig."),
          new Sample(
              true,
              true,
              "Satzung",
              "satzung.md",
              List.of("Fassung 2026"),
              "Abschn. Gebührenordnung › § 7 Gebühren",
              "# Gebührenordnung\n\n## § 7 Gebühren\n\n37,00 EUR"),
          new Sample(
              true,
              true,
              null,
              "city-0022_prag_altstadt-rundgang.pdf",
              List.of(),
              "S. 2–4",
              "Die Karlsbrücke verbindet die Altstadt mit der Kleinseite."),
          new Sample(
              true,
              false,
              "Merkblatt Wohnsitz",
              "merkblatt.md",
              List.of("Bürgerbüro"),
              null,
              "Die Anmeldung erfolgt binnen zwei Wochen."),
          new Sample(
              true,
              false,
              "Merkblatt Wohnsitz",
              "merkblatt.md",
              List.of(),
              null,
              "Die Anmeldung erfolgt binnen zwei Wochen."),
          new Sample(
              false,
              true,
              "Eintrag",
              "feed-entry",
              List.of("Fassung 2026"),
              "Abschn. Meldung",
              "Rathaus am Montag geschlossen."));

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
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("file_name", sample.fileName());
    if (sample.location() != null) {
      metadata.put(ChunkingService.LOCATION_METADATA_KEY, sample.location());
    }
    Document chunk = new Document(sample.chunkText(), metadata);
    String ingestTitle =
        sample.eligible() ? ChunkContextTitle.deriveTitle(sample.fileName()) : null;
    ChunkContextPrefix.applyTo(
        chunk,
        sample.eligible(),
        sample.documentWasSplit(),
        ChunkContextPrefix.titleAtRest(sample.eligible(), sample.coreTitle(), ingestTitle),
        sample.values());
    return chunk.getFormattedContent(MetadataMode.EMBED);
  }
}
