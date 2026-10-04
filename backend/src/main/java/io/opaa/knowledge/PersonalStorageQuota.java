package io.opaa.knowledge;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The storage quota across all private libraries of one person: one house-wide limit, set by the
 * system administration or else the configured default, and each person's own use against it. A
 * person's use is read only by the person herself and by the enforcement ({@code
 * ModularArchitecture#personalUsageIsReadOnlyByItsOwner}); the administration sees sums.
 */
@Service
public class PersonalStorageQuota {

  private final DocumentRepository documentRepository;
  private final PrivateStorageQuotaSettingRepository settingRepository;
  private final long configuredDefault;

  public PersonalStorageQuota(
      DocumentRepository documentRepository,
      PrivateStorageQuotaSettingRepository settingRepository,
      @Value("${opaa.library.private-storage-quota-bytes}") long configuredDefault) {
    this.documentRepository = documentRepository;
    this.settingRepository = settingRepository;
    this.configuredDefault = configuredDefault;
  }

  /** The bytes all private libraries of {@code userId} occupy together. */
  public long usageOf(UUID userId) {
    return documentRepository.sumFileSizeOfPrivateLibrariesOwnedBy(userId);
  }

  /** The limit in force; {@code 0} means unlimited. */
  public long quotaBytes() {
    return settingRepository
        .findSingleton()
        .map(PrivateStorageQuotaSetting::getQuotaBytes)
        .orElseGet(this::defaultQuotaBytes);
  }

  /** The configured default; a value {@code <= 0} means unlimited and reads as {@code 0}. */
  public long defaultQuotaBytes() {
    return Math.max(0, configuredDefault);
  }

  /** Whether the administration set its own value. */
  public boolean isOverridden() {
    return settingRepository.findSingleton().isPresent();
  }

  /**
   * Sets the house-wide limit; {@code null} returns to the configured default, {@code 0} means
   * unlimited.
   *
   * @throws IllegalArgumentException for a negative value
   */
  @Transactional
  public void setQuotaBytes(Long quotaBytes) {
    if (quotaBytes == null) {
      settingRepository.clear();
      return;
    }
    if (quotaBytes < 0) {
      throw new IllegalArgumentException("quota must not be negative: " + quotaBytes);
    }
    settingRepository.upsert(quotaBytes);
  }
}
