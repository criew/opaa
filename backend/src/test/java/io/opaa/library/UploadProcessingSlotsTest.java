package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.TooManyRequestsException;
import io.opaa.knowledge.UploadProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadProcessingSlotsTest {

  private final UUID busy = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();

  private static UploadProcessingSlots slots(int maxSize, int queue, int perAccount) {
    return new UploadProcessingSlots(
        new UploadProperties(
            null, null, 0, new UploadProperties.ThreadPool(1, maxSize, queue), 0, perAccount));
  }

  @Test
  void oneAccountHoldsAtMostItsShareWhileOthersStillFindAPlace() {
    UploadProcessingSlots slots = slots(2, 4, 2);

    slots.reserve(busy);
    UploadProcessingSlots.Slot second = slots.reserve(busy);
    assertThatThrownBy(() -> slots.reserve(busy))
        .isInstanceOfSatisfying(
            TooManyRequestsException.class,
            e ->
                assertThat(e.retryAfterSeconds())
                    .isEqualTo(UploadProcessingSlots.RETRY_AFTER_SECONDS))
        .hasMessage(UploadProcessingSlots.ACCOUNT_BUSY_MESSAGE);
    assertThat(slots.available()).as("the refusal takes no place").isEqualTo(4);

    slots.reserve(other);
    slots.reserve(other);
    assertThat(slots.available()).isEqualTo(2);

    second.release();
    second.release();
    slots.reserve(busy);
    assertThat(slots.available()).isEqualTo(2);
  }

  @Test
  void aFullPoolRefusesAnAccountBelowItsShare() {
    UploadProcessingSlots slots = slots(1, 1, 2);

    slots.reserve(busy);
    slots.reserve(other);

    assertThatThrownBy(() -> slots.reserve(UUID.randomUUID()))
        .isInstanceOf(TooManyRequestsException.class)
        .hasMessage(UploadProcessingSlots.BUSY_MESSAGE);
  }

  @Test
  void withoutASettingAnAccountHoldsAQuarterOfThePoolAndAtLeastOnePlace() {
    assertThat(
            new UploadProperties(null, null, 0, new UploadProperties.ThreadPool(2, 4, 20), 0)
                .maxPlacesPerAccount())
        .isEqualTo(6);
    assertThat(
            new UploadProperties(null, null, 0, new UploadProperties.ThreadPool(1, 1, 0), 0)
                .maxPlacesPerAccount())
        .isEqualTo(1);
  }
}
