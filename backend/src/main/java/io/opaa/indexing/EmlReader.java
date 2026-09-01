package io.opaa.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.SingleBody;
import org.apache.james.mime4j.dom.TextBody;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.dom.field.ContentTypeField;
import org.apache.james.mime4j.dom.field.FieldName;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.apache.james.mime4j.stream.MimeConfig;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads an EML message's Kopfdaten, body and attachments via {@code org.apache.james.mime4j.dom}
 * (docs/features/ingestion-pipelines.md, Teil 3, Punkt 5) - the header/body/attachment-text split
 * Tika's own RFC822 parser does not make, flattening all three into one text block instead.
 *
 * <p><b>Body selection.</b> The first {@code text/plain} part found is the body; a lone {@code
 * text/html} alternative is used only if no {@code text/plain} exists (stripped to text via Jsoup -
 * already a dependency, see {@link io.opaa.indexing.AttachmentProfile}). Inside a {@code
 * multipart/alternative} group, exactly one representation is chosen and the others are neither the
 * body nor an attachment - they are equivalent renderings of the same content, not distinct
 * documents. A nested {@code multipart/related} (e.g. HTML plus its inline images) inside an {@code
 * alternative} group is a known gap: it is neither selected as the body nor descended into for
 * attachments, since {@link #pickBestAlternative} only recognizes a leaf {@code text/plain}/{@code
 * text/html} part.
 *
 * <p><b>Attachment selection.</b> Every other leaf part - {@code Content-Disposition: attachment},
 * or any part carrying a file name at all (inline images routinely omit the disposition but still
 * name a file) - is an attachment, extracted to its own temp file for {@link MailDocumentPipeline}
 * to route through {@link DocumentPipelineRegistry}. A nested {@code message/rfc822} attachment (an
 * EML-in-EML forward) is re-serialized via {@link DefaultMessageWriter#writeMessage} into a
 * standalone temp file - a valid EML in its own right, read again by this same class one recursion
 * level deeper (see {@link MailDocumentPipeline}'s depth guard).
 */
final class EmlReader {

  private static final Logger log = LoggerFactory.getLogger(EmlReader.class);

  private EmlReader() {}

  static ParsedMailMessage read(Path file, MailProperties properties) throws IOException {
    // Header/body line length are deliberately unbounded here: the message file itself already
    // passed the ordinary upload/connector size checks (opaa.upload.max-file-size, library storage
    // quota) before reaching this pipeline - what those checks do not bound is an attachment
    // carved back out of an already-admitted container, which properties.maxAttachmentBytes()
    // guards instead (see MailAttachmentIo).
    MimeConfig config = MimeConfig.custom().setMaxLineLen(-1).setMaxHeaderLen(-1).build();
    Message.Builder builder = Message.Builder.of();
    builder.use(config);
    try (InputStream in = Files.newInputStream(file)) {
      builder.parse(in);
    }
    Message message = builder.build();

    BodyCollector collector = new BodyCollector();
    List<ParsedMailAttachment> attachments = new ArrayList<>();
    walk(message, collector, attachments, properties);

    return new ParsedMailMessage(
        message.getSubject(),
        renderMailboxList(message.getFrom()),
        message.getTo() != null ? renderMailboxList(message.getTo().flatten()) : null,
        message.getDate() != null ? message.getDate().toInstant() : null,
        collector.body != null ? collector.body : "",
        attachments);
  }

  private static final class BodyCollector {
    String body;
  }

  private static void walk(
      Entity entity,
      BodyCollector collector,
      List<ParsedMailAttachment> attachments,
      MailProperties properties)
      throws IOException {
    Body body = entity.getBody();
    if (body instanceof Multipart multipart) {
      if ("alternative".equalsIgnoreCase(multipart.getSubType())) {
        if (collector.body == null) {
          Entity best = pickBestAlternative(multipart.getBodyParts());
          if (best != null) {
            collector.body = renderTextEntity(best);
          }
        }
        return;
      }
      for (Entity child : multipart.getBodyParts()) {
        walk(child, collector, attachments, properties);
      }
      return;
    }
    if (isAttachment(entity)) {
      ParsedMailAttachment attachment = extractAttachment(entity, properties);
      if (attachment != null) {
        attachments.add(attachment);
      }
      return;
    }
    if (collector.body == null && isTextBodyCandidate(entity)) {
      collector.body = renderTextEntity(entity);
    }
  }

  private static Entity pickBestAlternative(List<Entity> parts) {
    Entity html = null;
    for (Entity part : parts) {
      if (isAttachment(part)) {
        continue;
      }
      String mimeType = part.getMimeType();
      if ("text/plain".equalsIgnoreCase(mimeType)) {
        return part;
      }
      if (html == null && "text/html".equalsIgnoreCase(mimeType)) {
        html = part;
      }
    }
    return html;
  }

  private static boolean isTextBodyCandidate(Entity entity) {
    String mimeType = entity.getMimeType();
    return "text/plain".equalsIgnoreCase(mimeType) || "text/html".equalsIgnoreCase(mimeType);
  }

  private static boolean isAttachment(Entity entity) {
    if ("attachment".equalsIgnoreCase(entity.getDispositionType())) {
      return true;
    }
    String fileName = resolveFilename(entity);
    return fileName != null && !fileName.isBlank();
  }

  private static String renderTextEntity(Entity entity) throws IOException {
    Body body = entity.getBody();
    if (!(body instanceof TextBody textBody)) {
      return "";
    }
    String text;
    try (Reader reader = textBody.getReader()) {
      StringBuilder builder = new StringBuilder();
      char[] buffer = new char[8192];
      int read;
      while ((read = reader.read(buffer)) != -1) {
        builder.append(buffer, 0, read);
      }
      text = builder.toString();
    }
    return "text/html".equalsIgnoreCase(entity.getMimeType()) ? Jsoup.parse(text).text() : text;
  }

  private static ParsedMailAttachment extractAttachment(Entity entity, MailProperties properties)
      throws IOException {
    String fileName = resolveFilename(entity);
    Body body = entity.getBody();
    boolean nestedMessage = body instanceof Message;
    if (fileName == null || fileName.isBlank()) {
      fileName = nestedMessage ? "nachricht.eml" : "anhang";
    }
    Path tempFile = MailAttachmentIo.createTempFile(fileName);
    try {
      if (nestedMessage) {
        try (OutputStream out = Files.newOutputStream(tempFile)) {
          new DefaultMessageWriter()
              .writeMessage(
                  (Message) body,
                  MailAttachmentIo.boundedOutputStream(out, properties.maxAttachmentBytes()));
        }
      } else if (body instanceof SingleBody singleBody) {
        try (InputStream in = singleBody.getInputStream();
            OutputStream out = Files.newOutputStream(tempFile)) {
          MailAttachmentIo.copyBounded(in, out, properties.maxAttachmentBytes());
        }
      } else {
        Files.deleteIfExists(tempFile);
        return null;
      }
    } catch (MailAttachmentIo.AttachmentTooLargeException e) {
      log.warn(
          "Skipping mail attachment {} exceeding the size limit of {} bytes",
          fileName,
          properties.maxAttachmentBytes());
      Files.deleteIfExists(tempFile);
      return null;
    } catch (IOException e) {
      Files.deleteIfExists(tempFile);
      throw e;
    }
    return new ParsedMailAttachment(fileName, tempFile);
  }

  /**
   * {@link Entity#getFilename()} only reads the {@code Content-Disposition} filename parameter;
   * older senders instead (or additionally) carry the name on {@code Content-Type}'s own {@code
   * name} parameter, so that is consulted as a fallback rather than treating such an attachment as
   * nameless.
   */
  private static String resolveFilename(Entity entity) {
    String filename = entity.getFilename();
    if (filename != null && !filename.isBlank()) {
      return filename;
    }
    ContentTypeField contentType =
        entity.getHeader().getField(FieldName.CONTENT_TYPE, ContentTypeField.class);
    return contentType != null ? contentType.getParameter("name") : null;
  }

  private static String renderMailboxList(MailboxList mailboxes) {
    if (mailboxes == null || mailboxes.isEmpty()) {
      return null;
    }
    return mailboxes.stream().map(EmlReader::renderMailbox).collect(Collectors.joining("; "));
  }

  private static String renderMailbox(Mailbox mailbox) {
    String name = mailbox.getName();
    return name != null && !name.isBlank()
        ? name + " <" + mailbox.getAddress() + ">"
        : mailbox.getAddress();
  }
}
