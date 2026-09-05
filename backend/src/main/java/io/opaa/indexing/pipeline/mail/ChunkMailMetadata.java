package io.opaa.indexing.pipeline.mail;

/**
 * Chunk metadata keys carrying an email's Kopfdaten (docs/features/ingestion-pipelines.md, Teil 3,
 * Punkt 5): Von, An, Betreff, Datum. Written by {@link MailDocumentPipeline} onto every one of a
 * message's own body chunks (never onto an attachment's recursively produced chunks), and copied
 * onto the persisted chunk by {@code FileProcessingService#storeChunks}.
 *
 * <p>{@code io.opaa.query.QueryService#mapSources} reads these back onto {@code ChatSource}, from
 * where {@code ChatResponseMapper} carries them into the generated {@code SourceReference} - the
 * Fundstellen-Anzeige's mail summary line. {@link MailDocumentPipeline} also renders the same
 * Kopfdaten as German-labeled context lines into the first body chunk's own text (see {@code
 * MailDocumentPipeline#headerContextText}), which is what reaches embedding and full-text search;
 * these structured fields are the separate, machine-readable copy the Fundstellen-Anzeige and a
 * structured Absender-/Zeitraum-/Betreff-Filterung need. {@code MAIL_DATE_METADATA_KEY} is written
 * truncated to whole seconds (an {@link java.time.Instant#toString()} rendering) so it stays
 * lexicographically sortable - {@link java.time.Instant#toString()} omits the fractional part
 * entirely when it is zero, which would otherwise make two close timestamps compare incorrectly as
 * text.
 *
 * <p>Public rather than package-private because {@code FileProcessingService} - outside this
 * pipeline's own package - reads these keys back when copying chunk metadata onto the persisted
 * chunk.
 */
public final class ChunkMailMetadata {

  public static final String MAIL_FROM_METADATA_KEY = "mail_from";
  public static final String MAIL_TO_METADATA_KEY = "mail_to";
  public static final String MAIL_SUBJECT_METADATA_KEY = "mail_subject";
  public static final String MAIL_DATE_METADATA_KEY = "mail_date";

  private ChunkMailMetadata() {}
}
