package io.opaa.indexing.metadata;

import java.util.List;

/**
 * What the "Metadatenfelder" section of a library's settings shows: its own fields with their value
 * lists, the Kontextpraefix-Wirkstellen of the core fields, and how many indexed documents wait for
 * the Kontextpraefix-Nachlauf. The last figure is a hint, not a control - the run itself is started
 * by a system administrator on the administration page.
 */
public record LibraryMetadataFieldOverview(
    List<LibraryMetadataFieldDefinition> fields,
    CoreContextPrefixSettings coreContextPrefix,
    long documentsAwaitingContextPrefixRerun) {}
