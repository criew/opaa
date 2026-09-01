package io.opaa.indexing;

import java.time.Instant;
import java.util.List;

/**
 * The Kopfdaten and body {@link EmlReader}/{@link MsgReader} extract from one EML/MSG message,
 * before {@link MailDocumentPipeline} turns it into chunks (docs/features/ingestion-pipelines.md,
 * Teil 3, Punkt 5).
 *
 * @param subject the message's Betreff, or {@code null} if it carries none
 * @param from a rendered "Display Name &lt;address&gt;" (or the address alone), or {@code null}
 * @param to every recipient rendered the same way as {@link #from}, joined with {@code "; "}, or
 *     {@code null} when the message names none
 * @param date the message's own Date header, or {@code null} when absent or unparsable
 * @param bodyText the message's own plain-text body - never the attachment text, which {@link
 *     #attachments()} carries separately for {@link MailDocumentPipeline} to route recursively
 * @param attachments every attachment found, in message order, each already written to its own
 *     temporary file (see {@link ParsedMailAttachment})
 */
record ParsedMailMessage(
    String subject,
    String from,
    String to,
    Instant date,
    String bodyText,
    List<ParsedMailAttachment> attachments) {}
