package io.opaa.indexing.source.confluence;

import io.opaa.knowledge.KnowledgeLibrary;
import java.util.UUID;

/**
 * Takes one Confluence webhook notification for {@link
 * ConfluenceSourceConnector#acceptNotification} - declared here so the connector does not know the
 * webhook adapter that implements it ({@code ConfluenceWebhookService}).
 */
public interface ConfluencePushReceiver {

  /**
   * Authenticates and queues the notification for {@code libraryId}; {@code loaded} is the library
   * the caller already loaded, {@code null} for none.
   */
  void accept(
      UUID libraryId,
      KnowledgeLibrary loaded,
      byte[] body,
      String hubSignature,
      String sharedSecret);
}
