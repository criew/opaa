package io.opaa.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AssetRole;
import io.opaa.permission.AccountStateHistory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/**
 * How {@link PointInTimeAccessService#unlockedPieces} cuts one access period by the locked
 * intervals of its person: the pieces are exactly the parts outside every lock, each as long as it
 * can be, and a piece reaching the window's end stays open.
 */
class UnlockedPiecesTest {

  private static final Instant FROM = Instant.parse("2026-03-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-04-01T00:00:00Z");
  private static final UUID PERSON = UUID.randomUUID();

  @Test
  void anEntryWithoutLocksStaysWhole() {
    assertThat(pieces(entry(FROM, null), List.of())).containsExactly(tuple(FROM, null));
  }

  @Test
  void aLockInsideThePeriodSplitsIt() {
    assertThat(pieces(entry(FROM, null), List.of(lock(day(5), day(10)))))
        .containsExactly(tuple(FROM, day(5)), tuple(day(10), null));
  }

  @Test
  void aLockThatBeganBeforeThePeriodAndEndsInsideItCutsItsStart() {
    assertThat(pieces(entry(FROM, null), List.of(lock(day(-3), day(4)))))
        .containsExactly(tuple(day(4), null));
  }

  @Test
  void anOpenLockCutsTheRestOfThePeriod() {
    assertThat(pieces(entry(FROM, day(20)), List.of(lock(day(8), null))))
        .containsExactly(tuple(FROM, day(8)));
  }

  @Test
  void aLockCoveringThePeriodRemovesIt() {
    assertThat(pieces(entry(day(2), day(6)), List.of(lock(day(1), day(7))))).isEmpty();
  }

  @Test
  void overlappingLocksCutAsOne() {
    assertThat(pieces(entry(FROM, null), List.of(lock(day(3), day(8)), lock(day(5), day(12)))))
        .containsExactly(tuple(FROM, day(3)), tuple(day(12), null));
  }

  @Test
  void adjoiningLocksLeaveNoEmptyPieceBetweenThem() {
    assertThat(pieces(entry(FROM, null), List.of(lock(day(3), day(8)), lock(day(8), day(12)))))
        .containsExactly(tuple(FROM, day(3)), tuple(day(12), null));
  }

  @Test
  void aLockOutsideTheEntryButInsideTheWindowCutsNothing() {
    assertThat(pieces(entry(day(10), day(20)), List.of(lock(day(2), day(5)), lock(day(25), null))))
        .containsExactly(tuple(day(10), day(20)));
  }

  @Test
  void separateLocksLeaveEveryPieceBetweenThem() {
    assertThat(
            pieces(
                entry(FROM, null),
                List.of(lock(day(2), day(4)), lock(day(9), day(11)), lock(day(20), day(22)))))
        .containsExactly(
            tuple(FROM, day(2)),
            tuple(day(4), day(9)),
            tuple(day(11), day(20)),
            tuple(day(22), null));
  }

  private static List<Tuple> pieces(AccessAsOfEntry entry, List<AccountStateHistory> locks) {
    return PointInTimeAccessService.unlockedPieces(entry, locks, TO).stream()
        .map(piece -> tuple(piece.validFrom(), piece.validTo()))
        .toList();
  }

  private static AccessAsOfEntry entry(Instant validFrom, Instant validTo) {
    return new AccessAsOfEntry(
        AccessBasis.DIRECT_GRANT,
        PERSON,
        null,
        null,
        null,
        AssetRole.VIEWER,
        null,
        validFrom,
        validTo);
  }

  private static AccountStateHistory lock(Instant validFrom, Instant validTo) {
    AccountStateHistory lock = mock(AccountStateHistory.class);
    when(lock.getUserId()).thenReturn(PERSON);
    when(lock.getValidFrom()).thenReturn(validFrom);
    when(lock.getValidTo()).thenReturn(validTo);
    return lock;
  }

  private static Instant day(int days) {
    return FROM.plus(Duration.ofDays(days));
  }
}
