/**
 * The data formats a single source delivers directly, without a file: {@link
 * io.opaa.indexing.format.DocumentFormat#handledFormats()} is empty, so routing never reaches them
 * and the source names the format itself through {@code DocumentIngest.pipelineId}.
 *
 * <p>The distinction to {@code file} is the delivery, not the syntax - Confluence storage format is
 * XHTML, but it arrives as an API response body, never as a file with an extension a user could
 * upload. A source that hands over an ordinary file uses {@code file} instead, the way RSS passes
 * detail pages to the HTML format.
 */
package io.opaa.indexing.format.stream;
