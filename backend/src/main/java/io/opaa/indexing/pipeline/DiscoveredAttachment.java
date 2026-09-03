package io.opaa.indexing.pipeline;

import java.nio.file.Path;

/**
 * One embedded object a {@link DocumentPipeline} found while parsing its own document but did not
 * itself turn into chunks - reported via {@link DocumentPipelineResult#discoveredAttachments()}
 * instead (ADR-0022, part 2). {@code tempFile} is not yet owned by any attachment path (that is
 * part 3, #1182): the caller of {@link DocumentPipeline#run} is responsible for deleting it once
 * this result is returned, whether or not it processes the attachment (see {@code
 * FileProcessingService}'s cleanup contract).
 *
 * @param fileName the attachment's own name, as carried by the parsed document - never blank
 * @param tempFile a temporary file holding the attachment's bytes, deleted by the caller of {@link
 *     DocumentPipeline#run}
 * @param detectedMediaType the media type detected for {@code tempFile}, or {@code null} if
 *     detection was not attempted before reporting the attachment
 */
public record DiscoveredAttachment(String fileName, Path tempFile, String detectedMediaType) {}
