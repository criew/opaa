package io.opaa.chat;

import io.opaa.compliance.DataImpact;
import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The bounds of chat attachments (docs/handbuch/spaces/suche.md, "Dokumente an eine Frage
 * anhängen"). The size of one file is the library upload's own {@code opaa.upload.max-file-size}.
 *
 * @param maxPerMessage attachments one question may name; default 10, from 1 to 10 (the API's own
 *     upper bound).
 * @param maxPerChat attachments one chat holds at most, earlier questions included; default 50.
 * @param maxProcessingPerAccount attachments of one account being read at once on this instance;
 *     default 4. A further upload is answered with 429 until one is done.
 * @param inlineMaxChars up to how many characters the attachments of a chat together go into an
 *     answer as full text; above it, each question gets the passages that fit it best. Default 120
 *     000 (roughly 30 000 tokens).
 * @param excerptMaxChars how many characters of passages a question gets above {@code
 *     inlineMaxChars}; default 40 000.
 * @param unboundRetention how long an attachment never sent with a question is kept before it is
 *     deleted; default one day, from one hour to seven days.
 * @param readWait how long the upload call waits for the text to be read before it answers with
 *     {@code PROCESSING}; default 8 seconds.
 */
@ConfigurationProperties(prefix = "opaa.chat.attachments")
public record ChatAttachmentProperties(
    @DefaultValue("10") @NoDataImpact("a usage limit") @InstallationWide int maxPerMessage,
    @DefaultValue("50") @NoDataImpact("a usage limit") @InstallationWide int maxPerChat,
    @DefaultValue("4") @NoDataImpact("a usage limit") @InstallationWide int maxProcessingPerAccount,
    @DefaultValue("120000") @DataImpact int inlineMaxChars,
    @DefaultValue("40000") @DataImpact int excerptMaxChars,
    @DefaultValue("24h") @DataImpact Duration unboundRetention,
    @DefaultValue("8s") @NoDataImpact("a waiting time of the request") @InstallationWide
        Duration readWait) {

  public static final int MAX_PER_MESSAGE_LIMIT = 10;

  public ChatAttachmentProperties {
    if (maxPerMessage < 1 || maxPerMessage > MAX_PER_MESSAGE_LIMIT) {
      throw new IllegalArgumentException(
          "opaa.chat.attachments.max-per-message must lie between 1 and "
              + MAX_PER_MESSAGE_LIMIT
              + ", got "
              + maxPerMessage);
    }
    if (maxPerChat < maxPerMessage) {
      throw new IllegalArgumentException(
          "opaa.chat.attachments.max-per-chat must be at least max-per-message, got " + maxPerChat);
    }
    if (maxProcessingPerAccount < 1) {
      throw new IllegalArgumentException(
          "opaa.chat.attachments.max-processing-per-account must be at least 1");
    }
    if (inlineMaxChars < 1000 || excerptMaxChars < 1000) {
      throw new IllegalArgumentException(
          "opaa.chat.attachments.inline-max-chars and excerpt-max-chars must be at least 1000");
    }
    if (unboundRetention.compareTo(Duration.ofHours(1)) < 0
        || unboundRetention.compareTo(Duration.ofDays(7)) > 0) {
      throw new IllegalArgumentException(
          "opaa.chat.attachments.unbound-retention must lie between 1h and 7d, got "
              + unboundRetention);
    }
    if (readWait.isNegative() || readWait.compareTo(Duration.ofSeconds(30)) > 0) {
      throw new IllegalArgumentException(
          "opaa.chat.attachments.read-wait must lie between 0 and 30s, got " + readWait);
    }
  }
}
