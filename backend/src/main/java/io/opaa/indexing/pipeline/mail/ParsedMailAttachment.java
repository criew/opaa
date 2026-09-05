package io.opaa.indexing.pipeline.mail;

import java.nio.file.Path;

/**
 * One attachment {@link EmlReader}/{@link MsgReader} extracted from a message, already written to a
 * temporary file - the caller ({@link MailDocumentPipeline}) is responsible for deleting {@link
 * #tempFile()} once it has routed the attachment through {@code DocumentPipelineRegistry}.
 *
 * @param fileName the attachment's own file name, as carried by the message - never blank (a reader
 *     that cannot resolve one names it itself, so {@code SupportedDocumentFormats} always has
 *     something to classify)
 * @param tempFile the extracted bytes - {@code null} only inside a reader, for an attachment
 *     selective extraction merely counted instead of writing; no such instance ever reaches {@link
 *     ParsedMailMessage#attachments()}
 */
record ParsedMailAttachment(String fileName, Path tempFile) {}
