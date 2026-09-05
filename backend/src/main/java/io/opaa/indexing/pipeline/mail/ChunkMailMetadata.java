package io.opaa.indexing.pipeline.mail;

/**
 * Chunk metadata keys carrying an email's Kopfdaten (ingestion-pipelines.md, Teil 3, Punkt 5): Von,
 * An, Betreff, Datum. Written by {@link MailDocumentPipeline} onto a message's own body chunks,
 * copied onto the persisted chunk by {@code FileProcessingService#storeChunks} and read back by
 * {@code io.opaa.query.QueryService#mapSources} for the Fundstellen-Anzeige - the structured,
 * machine-readable copy next to the context lines that reach embedding and full-text search.
 *
 * <p>{@code MAIL_DATE_METADATA_KEY} is truncated to whole seconds so it stays lexicographically
 * sortable: {@link java.time.Instant#toString()} omits a zero fractional part, which would
 * otherwise make two close timestamps compare wrongly as text.
 */
public final class ChunkMailMetadata {

  public static final String MAIL_FROM_METADATA_KEY = "mail_from";
  public static final String MAIL_TO_METADATA_KEY = "mail_to";
  public static final String MAIL_SUBJECT_METADATA_KEY = "mail_subject";
  public static final String MAIL_DATE_METADATA_KEY = "mail_date";

  private ChunkMailMetadata() {}
}
