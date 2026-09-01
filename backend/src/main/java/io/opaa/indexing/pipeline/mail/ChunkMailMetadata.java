package io.opaa.indexing.pipeline.mail;

/**
 * Chunk metadata keys carrying an email's Kopfdaten (docs/features/ingestion-pipelines.md, Teil 3,
 * Punkt 5): Von, An, Betreff, Datum belong on the chunk, never in its text - otherwise every chunk
 * of a Mailablage would embed the same Verteilerkopf. Written by {@link MailDocumentPipeline} onto
 * a message's own body chunks (never onto an attachment's recursively produced chunks), and copied
 * onto the persisted chunk by {@code FileProcessingService#storeChunks}.
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
