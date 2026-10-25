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

/** The house-wide value: the administration's own, else the factory default; 0 is unlimited. */
class PersonalStorageQuotaTest {

  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final PrivateStorageQuotaSettingRepository settings =
      mock(PrivateStorageQuotaSettingRepository.class);

  private static final long TEN_GIB = 10L * 1024 * 1024 * 1024;

  private PersonalStorageQuota quota() {
    return new PersonalStorageQuota(documentRepository, settings);
  }

  @Test
  void withoutAValueOfTheAdministrationTheFactoryDefaultOfTenGibApplies() {
    when(settings.findSingleton()).thenReturn(Optional.empty());

    assertThat(quota().quotaBytes()).isEqualTo(TEN_GIB);
    assertThat(quota().defaultQuotaBytes()).isEqualTo(TEN_GIB);
    assertThat(quota().isOverridden()).isFalse();
  }

  @Test
  void theValueOfTheAdministrationWins() {
    PrivateStorageQuotaSetting setting = Mockito.mock(PrivateStorageQuotaSetting.class);
    when(setting.getQuotaBytes()).thenReturn(0L);
    when(settings.findSingleton()).thenReturn(Optional.of(setting));

    assertThat(quota().quotaBytes()).isZero();
    assertThat(quota().isOverridden()).isTrue();
    assertThat(quota().defaultQuotaBytes()).isEqualTo(TEN_GIB);
  }

  @Test
  void nullReturnsToTheDefaultAndANegativeValueIsRefused() {
    PersonalStorageQuota quota = quota();

    quota.setQuotaBytes(null);
    verify(settings).clear();
    quota.setQuotaBytes(2048L);
    verify(settings).upsert(2048L);
    assertThatThrownBy(() -> quota.setQuotaBytes(-1L)).isInstanceOf(IllegalArgumentException.class);
    verify(settings, never()).upsert(-1L);
  }
}
