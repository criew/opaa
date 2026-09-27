package io.opaa.metadata;

import java.util.UUID;

/**
 * How many indexed documents of a library still wait for the Kontextpräfix Nachlauf - the hint the
 * field settings show. Implemented by the Nachlauf itself, which lies above this package.
 */
public interface ContextPrefixBacklog {

  long pendingDocuments(UUID libraryId);
}
