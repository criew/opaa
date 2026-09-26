package io.opaa.indexing.source;

import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Optional ability of a {@link SourceConnector}: serves the original of an indexed document for the
 * citation jump. The caller has decided access and resolved attachments to their root; a connector
 * without this ability has no original to serve.
 */
public interface OriginalAccess {

  /**
   * The original of {@code document}, which lives in {@code library}. Empty when none can be served
   * - gone, outside the library's configured source or unreachable in a way the caller must not
   * tell apart from "does not exist".
   *
   * @throws OriginalUnavailableException when the storage is only temporarily unreachable
   */
  Optional<DocumentContent> openOriginal(Document document, KnowledgeLibrary library);

  /**
   * The bound a streamed original of this type had to pass, so it can be buffered again for an
   * attachment's re-extraction. Empty for originals that are local files - the upload file-size
   * limit applies.
   */
  default OptionalLong streamedOriginalBound() {
    return OptionalLong.empty();
  }
}
