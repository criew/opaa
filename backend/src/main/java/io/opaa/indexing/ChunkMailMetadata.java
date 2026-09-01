package io.opaa.indexing;

/**
 * Chunk metadata keys carrying an email's Kopfdaten (docs/features/ingestion-pipelines.md, Teil 3,
 * Punkt 5): "Von, An, Betreff, Datum gehören an den Chunk, nicht in seinen Text - sonst embedded
 * jeder Chunk einer Mailablage denselben Verteilerkopf mit". Written by {@link
 * MailDocumentPipeline} onto the {@link org.springframework.ai.document.Document} chunks it
 * produces for a message's own body (never onto an attachment's recursively produced chunks, which
 * carry their own pipeline's structural context instead), and copied onto the persisted chunk by
 * {@code FileProcessingService#storeChunks} the same way {@link
 * ChunkingService#LOCATION_METADATA_KEY} is.
 */
final class ChunkMailMetadata {

  static final String MAIL_FROM_METADATA_KEY = "mail_from";
  static final String MAIL_TO_METADATA_KEY = "mail_to";
  static final String MAIL_SUBJECT_METADATA_KEY = "mail_subject";
  static final String MAIL_DATE_METADATA_KEY = "mail_date";

  private ChunkMailMetadata() {}
}
