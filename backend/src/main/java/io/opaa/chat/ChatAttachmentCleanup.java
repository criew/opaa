package io.opaa.chat;

import io.opaa.knowledge.UploadStoreUnavailableException;
import io.opaa.knowledge.UploadedOriginalRef;
import io.opaa.knowledge.UploadedOriginalStore;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps chat attachments within their lifetime: marks a reading that was lost (a restart while it
 * ran) as failed, deletes attachments never sent with a question after {@code
 * opaa.chat.attachments.unbound-retention}, and removes the originals whose row is gone - the
 * deletion of a chat takes its attachments by {@code ON DELETE CASCADE}, which cannot reach the
 * upload storage. An original younger than {@link #ORIGINAL_GRACE} is never touched: its row may be
 * being written this moment.
 */
@Service
public class ChatAttachmentCleanup {

  private static final Logger log = LoggerFactory.getLogger(ChatAttachmentCleanup.class);

  static final Duration LOST_READING_AFTER = Duration.ofMinutes(15);

  static final Duration ORIGINAL_GRACE = Duration.ofHours(1);

  static final String LOST_READING_MESSAGE =
      "Das Lesen wurde unterbrochen - bitte die Datei erneut anhängen.";

  private final ChatAttachmentRepository attachmentRepository;
  private final ChatAttachmentService attachmentService;
  private final OrganizationRepository organizationRepository;
  private final UploadedOriginalStore originalStore;
  private final ChatAttachmentProperties properties;
  private final Clock clock;

  public ChatAttachmentCleanup(
      ChatAttachmentRepository attachmentRepository,
      ChatAttachmentService attachmentService,
      OrganizationRepository organizationRepository,
      UploadedOriginalStore originalStore,
      ChatAttachmentProperties properties,
      Clock clock) {
    this.attachmentRepository = attachmentRepository;
    this.attachmentService = attachmentService;
    this.organizationRepository = organizationRepository;
    this.originalStore = originalStore;
    this.properties = properties;
    this.clock = clock;
  }

  /** One pass over all three; each part runs even when an earlier one failed. */
  public void runOnce() {
    Instant now = Instant.now(clock);
    try {
      failLostReadings(now);
    } catch (RuntimeException e) {
      log.warn("Marking lost chat attachment readings failed", e);
    }
    try {
      deleteUnbound(now);
    } catch (RuntimeException e) {
      log.warn("Deleting unsent chat attachments failed", e);
    }
    for (UUID organizationId : organizationIds()) {
      try {
        removeOrphanedOriginals(organizationId, now);
      } catch (UploadStoreUnavailableException e) {
        log.warn("Upload storage unavailable; chat attachment originals stay until the next run");
        return;
      } catch (RuntimeException e) {
        log.warn("Removing orphaned chat attachment originals failed", e);
      }
    }
  }

  int failLostReadings(Instant now) {
    int failed = 0;
    for (UUID id :
        attachmentRepository.findIdsByStatusBefore(
            io.opaa.api.types.ChatAttachmentStatus.PROCESSING, now.minus(LOST_READING_AFTER))) {
      failed += attachmentRepository.markFailed(id, LOST_READING_MESSAGE, now);
    }
    if (failed > 0) {
      log.info("Marked {} lost chat attachment reading(s) as failed", failed);
    }
    return failed;
  }

  int deleteUnbound(Instant now) {
    Instant before = now.minus(properties.unboundRetention());
    int deleted = 0;
    for (ChatAttachment attachment : attachmentRepository.findUnboundBefore(before)) {
      if (attachmentRepository.deleteIfUnboundBefore(attachment.getId(), before) == 1) {
        attachmentService.removeOriginal(attachment);
        deleted++;
      }
    }
    if (deleted > 0) {
      log.info("Deleted {} chat attachment(s) never sent with a question", deleted);
    }
    return deleted;
  }

  int removeOrphanedOriginals(UUID organizationId, Instant now) {
    Set<String> known = new HashSet<>(attachmentRepository.findOriginalLocators(organizationId));
    Instant threshold = now.minus(ORIGINAL_GRACE);
    List<String> orphaned = new ArrayList<>();
    originalStore.forEachStoredOriginal(
        organizationId,
        UploadedOriginalStore.CHAT_ATTACHMENT_AREA,
        original -> {
          if (!known.contains(original.locator()) && original.lastModified().isBefore(threshold)) {
            orphaned.add(original.locator());
          }
        });
    for (String locator : orphaned) {
      originalStore.delete(
          new UploadedOriginalRef(
              organizationId, UploadedOriginalStore.CHAT_ATTACHMENT_AREA, locator));
    }
    if (!orphaned.isEmpty()) {
      log.info("Removed {} chat attachment original(s) whose attachment is gone", orphaned.size());
    }
    return orphaned.size();
  }

  private List<UUID> organizationIds() {
    return organizationRepository.findAll().stream().map(Organization::getId).toList();
  }
}
