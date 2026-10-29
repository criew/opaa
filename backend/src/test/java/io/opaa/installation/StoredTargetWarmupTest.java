package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.MailEncryption;
import io.opaa.llm.ModelAddressPolicy;
import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailSettingsSnapshot;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The start-up check of stored targets (ADR-0063): every stored model endpoint and the stored mail
 * server pass the address check once, a refusal or a failure only warns, and the start does not
 * wait for it.
 */
class StoredTargetWarmupTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ModelAddressPolicy models = mock(ModelAddressPolicy.class);
  private final MailSettingsService mail = mock(MailSettingsService.class);
  private final InstallationMailTargets mailTargets = mock(InstallationMailTargets.class);
  private final StoredTargetWarmup warmup = new StoredTargetWarmup(jdbc, models, mail, mailTargets);

  @Test
  void everyStoredModelEndpointAndTheMailServerAreChecked() {
    when(jdbc.queryForList(anyString(), eq(String.class)))
        .thenReturn(Arrays.asList("https://chat.example/v1", null, "http://ollama:11434/v1"));
    when(models.blockedReason("http://ollama:11434/v1")).thenReturn("abgelehnt");
    when(mail.snapshot()).thenReturn(mailServer("smtp.example", 587));

    assertThatCode(warmup::warmUp).doesNotThrowAnyException();

    verify(models).blockedReason("https://chat.example/v1");
    verify(models).blockedReason("http://ollama:11434/v1");
    verify(mailTargets).guardStoredServer("smtp.example", 587);
  }

  @Test
  void aFailingReadOnlyWarnsAndTheMailServerIsStillChecked() {
    when(jdbc.queryForList(anyString(), eq(String.class)))
        .thenThrow(new DataAccessResourceFailureException("database down"));
    when(mail.snapshot()).thenReturn(mailServer("smtp.example", null));

    assertThatCode(warmup::warmUp).doesNotThrowAnyException();

    verify(mailTargets).guardStoredServer("smtp.example", 25);
  }

  @Test
  void anUnsendableMailConfigurationIsNotChecked() {
    when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
    when(mail.snapshot()).thenReturn(mailServer(null, null));

    warmup.warmUp();

    verify(mailTargets, never())
        .guardStoredServer(anyString(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void theStartDoesNotWaitForTheCheck() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch checked = new CountDownLatch(1);
    when(jdbc.queryForList(anyString(), eq(String.class)))
        .thenAnswer(
            invocation -> {
              release.await(10, TimeUnit.SECONDS);
              checked.countDown();
              return List.of();
            });

    warmup.onApplicationReady();

    assertThat(checked.getCount()).isOne();
    release.countDown();
    assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
  }

  private static MailSettingsSnapshot mailServer(String host, Integer port) {
    return new MailSettingsSnapshot(
        true, host, port, null, null, MailEncryption.STARTTLS, "opaa@example.org", null);
  }
}
