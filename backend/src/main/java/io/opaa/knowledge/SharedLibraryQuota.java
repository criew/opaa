package io.opaa.knowledge;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The storage quota per library: the operator's upper limit ({@link LibraryProperties#quotaBytes})
 * bounds every library, and below it the system administration may set one house-wide value for
 * shared libraries. A private library is held to the upper limit and its owner's {@link
 * PersonalStorageQuota}. Read from the database on every call, so a change applies on every
 * instance without a restart (ADR-0053, ADR-0060 Entscheidung 2).
 */
@Service
public class SharedLibraryQuota {

  private final LibraryProperties properties;
  private final SharedLibraryQuotaSettingRepository settingRepository;

  public SharedLibraryQuota(
      LibraryProperties properties, SharedLibraryQuotaSettingRepository settingRepository) {
    this.properties = properties;
    this.settingRepository = settingRepository;
  }

  /** The operator's upper limit per library; {@code 0} means none. */
  public long capBytes() {
    throw new UnsupportedOperationException();
  }

  /** The value the administration set, or {@code null} while it set none. */
  public Long ownQuotaBytes() {
    throw new UnsupportedOperationException();
  }

  /** The limit per shared library in force; {@code 0} means unlimited. */
  public long quotaBytes() {
    throw new UnsupportedOperationException();
  }

  /** The limit in force for {@code library}; {@code 0} means unlimited. */
  public long quotaBytesOf(KnowledgeLibrary library) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the house-wide value; {@code null} removes it.
   *
   * @throws IllegalArgumentException for a value below 1 or above the upper limit
   */
  @Transactional
  public void setOwnQuotaBytes(Long quotaBytes) {
    throw new UnsupportedOperationException();
  }
}
