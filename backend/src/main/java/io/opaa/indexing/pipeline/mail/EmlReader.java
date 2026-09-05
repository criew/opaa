package io.opaa.indexing.pipeline.mail;

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
 * (ingestion-pipelines.md, Teil 3, Punkt 5) - the split Tika's own RFC822 parser does not make. The
 * first {@code text/plain} part is the body, a lone {@code text/html} alternative only if none
 * exists; every other leaf part is an attachment, and a nested {@code message/rfc822} is
 * re-serialized and read again one recursion level deeper.
 *
 * <p>{@link MailProperties#maxAttachmentBytes()} bounds what reaches disk, not the parse itself -
 * mime4j holds the whole message in memory, so {@link MailProperties#maxMessageBytes()} is the
 * memory guard and is checked by the caller. With a {@code wantedIndex} only that one attachment is
 * materialized; positions are counted exactly as an unfiltered run counts them.
 */
final class EmlReader {

  private static final Logger log = LoggerFactory.getLogger(EmlReader.class);

  private EmlReader() {}

  static ParsedMailMessage read(Path file, MailProperties properties) throws IOException {
    return read(file, properties, null);
  }

  /**
   * {@code wantedIndex} restricts what is materialized: {@code null} means every attachment, a
   * negative value none at all - see this class' own Javadoc.
   */
  static ParsedMailMessage read(Path file, MailProperties properties, Integer wantedIndex)
      throws IOException {
    MimeConfig config = MimeConfig.custom().setMaxLineLen(-1).setMaxHeaderLen(-1).build();
    Message.Builder builder = Message.Builder.of();
    builder.use(config);
    try (InputStream in = Files.newInputStream(file)) {
      builder.parse(in);
    }
    Message message = builder.build();

    BodyCollector collector = new BodyCollector();
    List<ParsedMailAttachment> attachments = new ArrayList<>();
    AttachmentBudget budget = new AttachmentBudget(properties.maxAttachmentsPerMessage());
    ExtractionPosition position = new ExtractionPosition(wantedIndex);
    try {
      walk(message, collector, attachments, properties, budget, position);
    } catch (IOException | RuntimeException e) {
      // Whatever this pass already extracted must not leak as an orphaned temp file just because a
      // later part in the same message failed to read - the caller never
      // gets a ParsedMailMessage to clean these up itself, since this call never returns one.
      for (ParsedMailAttachment attachment : attachments) {
        deleteQuietly(attachment.tempFile());
      }
      throw e;
    }
    if (budget.exhausted()) {
      log.warn(
          "{} carries more than the configured limit of {} attachments; the remainder was never"
              + " extracted (opaa.indexing.mail.max-attachments-per-message)",
          file,
          properties.maxAttachmentsPerMessage());
    }

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

  /**
   * Counts the extraction positions an unfiltered run would produce and decides which of them is
   * materialized - a position is only consumed by an attachment that was actually read, exactly the
   * numbering {@code FileProcessingService#attachmentFilePath} persists.
   */
  private static final class ExtractionPosition {
    private final Integer wanted;
    private int next;

    ExtractionPosition(Integer wanted) {
      this.wanted = wanted;
    }

    boolean materializeNext() {
      return wanted == null || wanted == next;
    }

    void advance() {
      next++;
    }
  }

  /**
   * One attachment part read successfully; {@code tempFile} is {@code null} when selective
   * extraction only counted it instead of writing it to disk.
   */
  private record ReadAttachment(String fileName, Path tempFile) {}

  private static void walk(
      Entity entity,
      BodyCollector collector,
      List<ParsedMailAttachment> attachments,
      MailProperties properties,
      AttachmentBudget budget,
      ExtractionPosition position)
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
        walk(child, collector, attachments, properties, budget, position);
      }
      return;
    }
    if (isAttachment(entity)) {
      if (!budget.hasCapacity()) {
        // Not extracted at all - no temp file is ever created for an attachment beyond the
        // configured limit.
        return;
      }
      budget.reserve();
      ReadAttachment attachment = extractAttachment(entity, properties, position.materializeNext());
      if (attachment != null) {
        position.advance();
        if (attachment.tempFile() != null) {
          attachments.add(new ParsedMailAttachment(attachment.fileName(), attachment.tempFile()));
        }
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

  /**
   * Extracts one attachment to its own temp file (or, with {@code materialize} {@code false},
   * merely reads it under the same size bound without writing it anywhere), or {@code null} when it
   * could not be read at all - a malformed part (mime4j throwing while decoding it) must only cost
   * this one attachment, never the whole message's extraction. A {@code null} return also skips an
   * extraction position, exactly as it did before selective extraction.
   */
  private static ReadAttachment extractAttachment(
      Entity entity, MailProperties properties, boolean materialize) throws IOException {
    String fileName = resolveFilename(entity);
    Body body;
    try {
      body = entity.getBody();
    } catch (RuntimeException e) {
      log.warn("Skipping a mail attachment that could not be read", e);
      return null;
    }
    boolean nestedMessage = body instanceof Message;
    if (fileName == null || fileName.isBlank()) {
      fileName = nestedMessage ? "nachricht.eml" : "anhang";
    }
    if (!nestedMessage && !(body instanceof SingleBody)) {
      return null;
    }
    Path tempFile = materialize ? MailAttachmentIo.createTempFile(fileName) : null;
    try (OutputStream out =
        tempFile != null ? Files.newOutputStream(tempFile) : OutputStream.nullOutputStream()) {
      if (nestedMessage) {
        new DefaultMessageWriter()
            .writeMessage(
                (Message) body,
                MailAttachmentIo.boundedOutputStream(out, properties.maxAttachmentBytes()));
      } else {
        try (InputStream in = ((SingleBody) body).getInputStream()) {
          MailAttachmentIo.copyBounded(in, out, properties.maxAttachmentBytes());
        }
      }
    } catch (MailAttachmentIo.AttachmentTooLargeException e) {
      log.warn(
          "Skipping mail attachment {} exceeding the size limit of {} bytes",
          fileName,
          properties.maxAttachmentBytes());
      deleteIfCreated(tempFile);
      return null;
    } catch (IOException e) {
      deleteIfCreated(tempFile);
      throw e;
    } catch (RuntimeException e) {
      // A malformed part (e.g. mime4j failing mid-decode on a truncated or corrupt attachment)
      // costs only this attachment, not the whole message.
      log.warn("Skipping mail attachment {} that could not be read", fileName, e);
      deleteIfCreated(tempFile);
      return null;
    }
    return new ReadAttachment(fileName, tempFile);
  }

  private static void deleteIfCreated(Path tempFile) throws IOException {
    if (tempFile != null) {
      Files.deleteIfExists(tempFile);
    }
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

  /**
   * Logs rather than throws on a failed delete - called from a {@code catch} block that is already
   * about to rethrow the real failure; a secondary I/O error here must not replace it.
   */
  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Failed to delete temp file: {}", file, e);
    }
  }
}
