package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.notification.NotificationService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * A profile has no organization, so the private libraries it releases may belong to several; their
 * number is exact only where owners and other owners each reach the minimum group size 5 in every
 * one of them, whatever order the libraries come in.
 */
class PrivateLibraryReleaseCountTest {

  private static final UUID X = UUID.randomUUID();
  private static final UUID Y = UUID.randomUUID();

  private final KnowledgeLibraryRepository libraries = mock(KnowledgeLibraryRepository.class);
  private final PrivateLibraryRelease release =
      new PrivateLibraryRelease(
          mock(LibraryConnectionRepository.class),
          libraries,
          mock(SourceTransitions.class),
          mock(NotificationService.class),
          Clock.systemUTC());

  /** Regression guard for #2270: one owner in Y is not hidden behind four in X. */
  @Test
  void fourOwnersInOneOrganizationAndOneInAnotherAreNotToldExactly() {
    otherOwners(X, 5);
    otherOwners(Y, 5);
    List<KnowledgeLibrary> released = new ArrayList<>(owned(X, 4));
    released.addAll(owned(Y, 1));

    assertThat(release.count(released, TestPersonCounts.numbers())).isNull();
    assertThat(release.count(released.reversed(), TestPersonCounts.numbers())).isNull();
  }

  @Test
  void enoughOwnersAndOtherOwnersInEveryOrganizationAreToldExactly() {
    otherOwners(X, 5);
    otherOwners(Y, 6);
    List<KnowledgeLibrary> released = new ArrayList<>(owned(X, 5));
    released.addAll(owned(Y, 5));

    assertThat(release.count(released, TestPersonCounts.numbers()).count()).isEqualTo(10);
  }

  /**
   * Either organization lacking other owners withholds the number, so no single organization -
   * whichever the grouping visits first or last - decides it.
   */
  @Test
  void noOtherOwnerInEitherOrganizationCountsAsFew() {
    List<KnowledgeLibrary> released = new ArrayList<>(owned(X, 5));
    released.addAll(owned(Y, 5));

    otherOwners(X, 5);
    otherOwners(Y, 0);
    assertThat(release.count(released, TestPersonCounts.numbers())).as("Y without").isNull();

    otherOwners(X, 0);
    otherOwners(Y, 5);
    assertThat(release.count(released, TestPersonCounts.numbers())).as("X without").isNull();
  }

  private void otherOwners(UUID organization, long count) {
    when(libraries.countPrivateLibraryOwnersOutside(eq(organization), any())).thenReturn(count);
  }

  /** One private library each of {@code owners} persons of {@code organization}. */
  private static List<KnowledgeLibrary> owned(UUID organization, int owners) {
    return IntStream.range(0, owners)
        .mapToObj(
            index ->
                KnowledgeLibrary.ownerOnly(
                    organization,
                    "Meine Ablage " + index,
                    null,
                    UUID.randomUUID(),
                    PersonProbeSourceConnector.TYPE,
                    null,
                    "https://person.example.org/privat",
                    null,
                    null,
                    false))
        .toList();
  }
}
