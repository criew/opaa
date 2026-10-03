package io.opaa.indexing.filesync;

/**
 * One file a notification reported, resolved by the connector to its container.
 *
 * @param filePath the document identity, what a confirmed deletion removes
 */
public record FileReference(FileContainer container, String id, String filePath) {}
