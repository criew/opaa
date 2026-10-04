package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.source.RunCredentials;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link SecretCheckedStore} asks the run's credentials before a resumption as before every other
 * access, and passes the store's absence proof through.
 */
class SecretCheckedStoreTest {

  private final InMemoryFileStore store =
      new InMemoryFileStore()
          .withCheckpoints()
          .absenceProof(AbsenceProof.LOCATION_IDENTITY)
          .container("A")
          .put("A", "a.txt", "A.");

  @Test
  void aResumptionAsksTheCredentialsFirst() throws Exception {
    AtomicInteger asked = new AtomicInteger();
    RunCredentials credentials =
        new RunCredentials(
            () -> {
              asked.incrementAndGet();
              return Secret.personal("geheim");
            },
            Duration.ZERO,
            Clock.systemUTC());
    SecretCheckedStore checked = new SecretCheckedStore(store, credentials);

    FilePage page = checked.resume(new FileContainer("A"), "g0:");

    assertThat(asked).hasValue(1);
    assertThat(page.entries()).extracting(FileEntry::fileName).containsExactly("a.txt");
    assertThat(checked.absenceProof()).isEqualTo(AbsenceProof.LOCATION_IDENTITY);
  }

  @Test
  void aBlockedSourceRefusesTheResumptionBeforeTheStoreSeesIt() {
    RunCredentials credentials =
        new RunCredentials(
            () -> {
              throw new SourceConnectionBlockedException(
                  new SourceBlock(
                      SourceBlock.Reason.ACCESS_REMOVED,
                      "Verwaltende der Bibliothek",
                      "Zugang entfernt: Der Zugang dieser Bibliothek wurde gelöscht."));
            },
            Duration.ZERO,
            Clock.systemUTC());
    SecretCheckedStore checked = new SecretCheckedStore(store, credentials);

    assertThatThrownBy(() -> checked.resume(new FileContainer("A"), "g0:"))
        .isInstanceOf(SourceConnectionBlockedException.class);
    assertThat(store.calls()).isEmpty();
  }
}
