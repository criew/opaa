package io.opaa.indexing.metadata;

import java.util.List;

/**
 * What the "Metadatenfelder" section of a library's settings shows: its own fields with their value
 * lists, the Kontextpraefix-Wirkstellen of the core fields, how many indexed documents wait for the
 * Kontextpraefix-Nachlauf, and the schema changes currently running over the bestand. The
 * Kontextpraefix figure is a hint, not a control - that run is started by a system administrator on
 * the administration page; the pending schema changes are continued from here, by whoever holds the
 * management right.
 */
public record LibraryMetadataFieldOverview(
    List<LibraryMetadataFieldDefinition> fields,
    CoreContextPrefixSettings coreContextPrefix,
    long documentsAwaitingContextPrefixRerun,
    List<LibraryMetadataSchemaChangeView> pendingSchemaChanges) {}
