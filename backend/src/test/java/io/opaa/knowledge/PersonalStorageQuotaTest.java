package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** The house-wide value: the administration's own, else the configured default; 0 is unlimited. */
class PersonalStorageQuotaTest {

  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final PrivateStorageQuotaSettingRepository settings =
      mock(PrivateStorageQuotaSettingRepository.class);

  private PersonalStorageQuota quota(long configuredDefault) {
    return new PersonalStorageQuota(documentRepository, settings, configuredDefault);
  }

  @Test
  void withoutAValueOfTheAdministrationTheConfiguredDefaultApplies() {
    when(settings.findSingleton()).thenReturn(Optional.empty());

    assertThat(quota(4096).quotaBytes()).isEqualTo(4096);
    assertThat(quota(4096).isOverridden()).isFalse();
    assertThat(quota(-1).quotaBytes()).isZero();
    assertThat(quota(-1).defaultQuotaBytes()).isZero();
  }

  @Test
  void theValueOfTheAdministrationWins() {
    PrivateStorageQuotaSetting setting = Mockito.mock(PrivateStorageQuotaSetting.class);
    when(setting.getQuotaBytes()).thenReturn(0L);
    when(settings.findSingleton()).thenReturn(Optional.of(setting));

    assertThat(quota(4096).quotaBytes()).isZero();
    assertThat(quota(4096).isOverridden()).isTrue();
    assertThat(quota(4096).defaultQuotaBytes()).isEqualTo(4096);
  }

  @Test
  void nullReturnsToTheDefaultAndANegativeValueIsRefused() {
    PersonalStorageQuota quota = quota(4096);

    quota.setQuotaBytes(null);
    verify(settings).clear();
    quota.setQuotaBytes(2048L);
    verify(settings).upsert(2048L);
    assertThatThrownBy(() -> quota.setQuotaBytes(-1L)).isInstanceOf(IllegalArgumentException.class);
    verify(settings, never()).upsert(-1L);
  }
}
