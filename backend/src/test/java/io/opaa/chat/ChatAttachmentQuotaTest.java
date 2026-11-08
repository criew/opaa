package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.common.PayloadTooLargeException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ChatAttachmentQuotaTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();
  private static final UUID AUTHOR = UUID.randomUUID();

  private final ChatAttachmentRepository repository = mock(ChatAttachmentRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  @Test
  void theOrganizationsQuotaRefusesWhatTheAccountWouldStillTake() {
    when(repository.sumSizeByOrganizationId(ORGANIZATION)).thenReturn(990L);
    ChatAttachmentQuota quota = quota(0, 0, 1000);

    assertThatThrownBy(() -> quota.requireRoom(ORGANIZATION, AUTHOR, 11))
        .isInstanceOf(PayloadTooLargeException.class)
        .hasMessageContaining("Organisation");
    assertThatCode(() -> quota.requireRoom(ORGANIZATION, AUTHOR, 10)).doesNotThrowAnyException();
  }

  @Test
  void zeroLiftsEveryQuota() {
    when(repository.sumSizeByAuthorId(AUTHOR)).thenReturn(Long.MAX_VALUE / 2);
    when(repository.sumUnboundSizeByAuthorId(AUTHOR)).thenReturn(Long.MAX_VALUE / 2);
    when(repository.sumSizeByOrganizationId(ORGANIZATION)).thenReturn(Long.MAX_VALUE / 2);

    assertThatCode(() -> quota(0, 0, 0).requireRoom(ORGANIZATION, AUTHOR, 1))
        .doesNotThrowAnyException();
  }

  @Test
  void theIntakeLocksTheOrganizationWhileItsQuotaIsSetAndTheAccountOtherwise() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

    quota(1000, 100, 5000).admit(ORGANIZATION, AUTHOR, 1, () -> null);
    verify(repository).lockIntake(ORGANIZATION);

    quota(1000, 100, 0).admit(ORGANIZATION, AUTHOR, 1, () -> null);
    verify(repository).lockIntake(AUTHOR);
  }

  @Test
  void theUnboundCapMayNotExceedTheAccountsQuota() {
    assertThatThrownBy(() -> properties(100, 200, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unbound-quota-bytes");
    assertThatThrownBy(() -> properties(100, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  private ChatAttachmentQuota quota(long account, long unbound, long organization) {
    return new ChatAttachmentQuota(
        repository, properties(account, unbound, organization), transactions);
  }

  private static ChatAttachmentProperties properties(
      long account, long unbound, long organization) {
    return new ChatAttachmentProperties(
        10,
        50,
        4,
        120_000,
        40_000,
        Duration.ofHours(24),
        Duration.ofSeconds(8),
        account,
        unbound,
        organization);
  }
}
