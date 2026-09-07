package io.opaa.indexing.source.s3;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A downloaded object: the temporary file <b>the caller deletes</b> plus what the {@code GetObject}
 * response said about it.
 *
 * @param eTag without surrounding quotes; {@code null} when the store sent none
 * @param size the bytes actually written to {@code file}
 */
public record S3Download(
    Path file, String contentType, String eTag, long size, Instant lastModified) {}
