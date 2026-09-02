package io.opaa.indexing.pipeline.mail;

/**
 * Chunk metadata keys carrying an email's Kopfdaten (docs/features/ingestion-pipelines.md, Teil 3,
 * Punkt 5): Von, An, Betreff, Datum. Written by {@link MailDocumentPipeline} onto every one of a
 * message's own body chunks (never onto an attachment's recursively produced chunks), and copied
 * onto the persisted chunk by {@code FileProcessingService#storeChunks}.
 *
 * <p><b>These fields have no reader today - a deliberate vorhaltung, not an oversight</b> (#1130
 * Befund 1). {@link MailDocumentPipeline} also renders the same Kopfdaten as German-labeled context
 * lines into the first body chunk's own text (see {@code MailDocumentPipeline#headerContextText}),
 * which is what reaches embedding and full-text search today. These structured fields stay in
 * addition, unread, as the basis for the Fundstellen-Anzeige and structured Absender-/Zeitraum-/
 * Betreff-Filterung tracked in #1164.
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
