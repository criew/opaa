package io.opaa.library.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.PayloadTooLargeException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The shared raw-body bound of the push intake, without a Spring context. */
class PushIntakeControllerTest {

  /**
   * The premise the raw-body scan of {@code io.opaa.api.TransportStatusCodeSpecificationTest} rests
   * on: the bound the intakes share really does refuse an oversized body, so {@code 413} is a
   * status they can actually answer.
   */
  @Test
  void theSharedRawBodyBoundRefusesAnOversizedBody() {
    MockHttpServletRequest oversized = new MockHttpServletRequest("POST", "/api/v1/libraries/x");
    oversized.setContent(new byte[PushIntakeController.MAX_BODY_BYTES + 1]);

    assertThatThrownBy(() -> PushIntakeController.readBounded(oversized))
        .as("the shared bound no longer refuses an oversized body")
        .isInstanceOf(PayloadTooLargeException.class);
  }
}
