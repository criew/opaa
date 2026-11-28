package io.opaa.indexing.document;

import java.util.UUID;

/**
 * An upload's row no longer belongs to this ingest: it left {@code PENDING}, names another
 * instance, or this instance lost its heartbeat (ADR-0053, Entscheidung 8). Asked before an ingest
 * is repeated; the ingest ends where this is thrown and writes nothing more to the row.
 */
public class IngestNoLongerHeldException extends RuntimeException {

  public IngestNoLongerHeldException(UUID documentId, String reason) {
    super("Document " + documentId + " is no longer held by this ingest: " + reason);
  }
}
