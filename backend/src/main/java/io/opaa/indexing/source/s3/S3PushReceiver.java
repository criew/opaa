package io.opaa.indexing.source.s3;

import io.opaa.knowledge.KnowledgeLibrary;
import java.util.UUID;

/**
 * Takes one S3 event notification for {@link S3SourceConnector#acceptNotification} - declared here
 * so the connector does not know the event adapter that implements it ({@code S3EventService}).
 */
public interface S3PushReceiver {

  /**
   * Authenticates and queues the notification for {@code libraryId}; {@code loaded} is the library
   * the caller already loaded, {@code null} for none.
   */
  void accept(
      UUID libraryId,
      KnowledgeLibrary loaded,
      byte[] body,
      String authorization,
      String sharedSecret);
}
