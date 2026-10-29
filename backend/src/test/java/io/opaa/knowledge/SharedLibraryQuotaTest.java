package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The quota per library: the operator's upper limit bounds everything, the administration's own
 * value applies below it to shared libraries only.
 */
class SharedLibraryQuotaTest {

  private static final long TEN_GIB = 10L * 1024 * 1024 * 1024;

  private final SharedLibraryQuotaSettingRepository settings =
      mock(SharedLibraryQuotaSettingRepository.class);

  private SharedLibraryQuota quota(long capBytes) {
    return new SharedLibraryQuota(new LibraryProperties(capBytes), settings);
  }

  private void ownValue(long bytes) {
    SharedLibraryQuotaSetting setting = mock(SharedLibraryQuotaSetting.class);
    when(setting.getQuotaBytes()).thenReturn(bytes);
    when(settings.findSingleton()).thenReturn(Optional.of(setting));
  }

  private static KnowledgeLibrary shared() {
    return KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Geteilt", null, UUID.randomUUID());
  }

  private static KnowledgeLibrary privateLibrary() {
    return KnowledgeLibrary.ownerOnly(
        UUID.randomUUID(), "Privat", null, UUID.randomUUID(), SourceType.UPLOAD, null, null, null);
  }

  @Test
  void withoutAnOwnValueTheUpperLimitAppliesAlone() {
    when(settings.findSingleton()).thenReturn(Optional.empty());

    SharedLibraryQuota quota = quota(TEN_GIB);

    assertThat(quota.ownQuotaBytes()).isNull();
    assertThat(quota.capBytes()).isEqualTo(TEN_GIB);
    assertThat(quota.quotaBytes()).isEqualTo(TEN_GIB);
    assertThat(quota.quotaBytesOf(shared())).isEqualTo(TEN_GIB);
  }

  @Test
  void anOwnValueBelowTheUpperLimitAppliesToSharedLibrariesOnly() {
    ownValue(2048);

    SharedLibraryQuota quota = quota(TEN_GIB);

    assertThat(quota.ownQuotaBytes()).isEqualTo(2048L);
    assertThat(quota.quotaBytes()).isEqualTo(2048);
    assertThat(quota.quotaBytesOf(shared())).isEqualTo(2048);
    assertThat(quota.quotaBytesOf(privateLibrary())).isEqualTo(TEN_GIB);
  }

  /** The operator lowered the upper limit below the stored value: the upper limit wins. */
  @Test
  void anUpperLimitBelowTheOwnValueWins() {
    ownValue(5000);

    assertThat(quota(1000).quotaBytes()).isEqualTo(1000);
    assertThat(quota(1000).ownQuotaBytes()).isEqualTo(5000L);
  }

  @Test
  void withoutAnUpperLimitTheOwnValueAppliesAndWithoutEitherNothingDoes() {
    ownValue(4096);
    assertThat(quota(0).capBytes()).isZero();
    assertThat(quota(0).quotaBytes()).isEqualTo(4096);
    assertThat(quota(-1).capBytes()).isZero();
    assertThat(quota(-1).quotaBytesOf(privateLibrary())).isZero();

    when(settings.findSingleton()).thenReturn(Optional.empty());
    assertThat(quota(0).quotaBytes()).isZero();
  }

  @Test
  void theOwnValueIsSetOrRemovedWithinTheUpperLimit() {
    SharedLibraryQuota quota = quota(1000);

    quota.setOwnQuotaBytes(null);
    verify(settings).clear();
    quota.setOwnQuotaBytes(1000L);
    verify(settings).upsert(1000L);
    assertThatThrownBy(() -> quota.setOwnQuotaBytes(1001L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> quota.setOwnQuotaBytes(0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> quota.setOwnQuotaBytes(-5L))
        .isInstanceOf(IllegalArgumentException.class);
    verify(settings, never()).upsert(1001L);
  }

  @Test
  void withoutAnUpperLimitAnyPositiveValueIsAccepted() {
    quota(0).setOwnQuotaBytes(Long.MAX_VALUE);

    verify(settings).upsert(anyLong());
  }
}
