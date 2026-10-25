package io.opaa.knowledge;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The storage quota across all private libraries of one person: one house-wide limit, set by the
 * system administration or else the factory default {@link #DEFAULT_QUOTA_BYTES}, and each person's
 * own use against it. A person's use is read only by the person herself and by the enforcement
 * ({@code ModularArchitecture#personalUsageIsReadOnlyByItsOwner}); the administration sees sums.
 */
@Service
public class PersonalStorageQuota {

  /** The factory default, 10 GiB per person, while the administration has set no value. */
  public static final long DEFAULT_QUOTA_BYTES = 10L * 1024 * 1024 * 1024;

  private final DocumentRepository documentRepository;
  private final PrivateStorageQuotaSettingRepository settingRepository;

  public PersonalStorageQuota(
      DocumentRepository documentRepository,
      PrivateStorageQuotaSettingRepository settingRepository) {
    this.documentRepository = documentRepository;
    this.settingRepository = settingRepository;
  }

  /**
   * The bytes all private libraries of {@code userId} occupy together - one marked for erasure
   * included until its erasure has removed its documents.
   */
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

  /** The factory default {@link #DEFAULT_QUOTA_BYTES}. */
  public long defaultQuotaBytes() {
    return DEFAULT_QUOTA_BYTES;
  }

  /** Whether the administration set its own value. */
  public boolean isOverridden() {
    return settingRepository.findSingleton().isPresent();
  }

  /**
   * Sets the house-wide limit; {@code null} returns to the factory default, {@code 0} means
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
