package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import io.opaa.api.AnonymousSurface.Answer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The verdict of {@link AnonymousSurfaceIntegrationTest} without a Spring context: a new anonymous
 * answer without an allowlist entry fails, a refusal passes, a missing security header fails, and
 * the allowlist itself carries a reason for every entry and no entry that the application outgrew.
 */
class AnonymousSurfaceTest {

  private static final AnonymousSurface ALLOWLIST =
      AnonymousSurface.of(
          Map.of(
              "anonymous",
              List.of(Map.of("operation", "GET /api/health", "reason", "Betriebsprüfung"))));

  @Test
  void aNewOperationAnsweringWithoutASessionFails() {
    List<String> violations =
        ALLOWLIST.violations(
            List.of(
                answer("GET /api/health", 200), answer("GET /api/v1/libraries/{libraryId}", 200)));

    assertThat(violations)
        .singleElement(STRING)
        .startsWith("GET /api/v1/libraries/{libraryId} answered 200 with 2 bytes");
  }

  @Test
  void aSuccessWithoutBodyFailsAsWell() {
    assertThat(
            ALLOWLIST.violations(List.of(answer("GET /api/health", 200), answer("DELETE /x", 204))))
        .singleElement(STRING)
        .startsWith("DELETE /x answered 204");
  }

  @Test
  void refusalsPass() {
    assertThat(
            ALLOWLIST.violations(
                List.of(
                    answer("GET /api/health", 200),
                    answer("GET /a", 401),
                    answer("GET /b", 403),
                    answer("GET /c", 404))))
        .isEmpty();
  }

  @Test
  void anyOtherStatusOfAnUnlistedOperationFails() {
    assertThat(
            ALLOWLIST.violations(
                List.of(
                    answer("GET /api/health", 200), answer("POST /a", 400), answer("GET /b", 500))))
        .hasSize(2);
  }

  @Test
  void anEntryWhoseOperationVanishedFails() {
    assertThat(ALLOWLIST.violations(List.of(answer("GET /a", 401))))
        .singleElement(STRING)
        .startsWith("GET /api/health is on");
  }

  @Test
  void droppingAnEntryTurnsItsAnswerIntoAViolation() {
    assertThat(
            ALLOWLIST
                .without("GET /api/health")
                .violations(List.of(answer("GET /api/health", 200))))
        .singleElement(STRING)
        .startsWith("GET /api/health answered 200");
  }

  @Test
  void aMissingNosniffFails() {
    Answer answer = new Answer("GET /api/health", "/api/health", 200, 2, null, "DENY", null);

    assertThat(ALLOWLIST.violations(List.of(answer)))
        .singleElement(STRING)
        .contains("X-Content-Type-Options");
  }

  @Test
  void aMissingFrameProtectionFails() {
    Answer answer =
        new Answer("GET /api/health", "/api/health", 401, 0, "nosniff", null, "default-src 'none'");

    assertThat(ALLOWLIST.violations(List.of(answer)))
        .singleElement(STRING)
        .contains("no frame protection");
  }

  @Test
  void frameAncestorsAloneIsAFrameProtection() {
    Answer answer =
        new Answer(
            "GET /api/health", "/api/health", 200, 2, "nosniff", null, "frame-ancestors 'self'");

    assertThat(ALLOWLIST.violations(List.of(answer))).isEmpty();
  }

  @Test
  void aListedHeaderGapPassesAndAClosedOneFails() {
    AnonymousSurface allowlist =
        AnonymousSurface.of(
            Map.of(
                "missingHeaders",
                List.of(Map.of("operation", "GET /gap", "reason", "bis zur Behebung"))));
    Answer gap = new Answer("GET /gap", "/gap", 404, 2, null, null, null);

    assertThat(allowlist.violations(List.of(gap))).isEmpty();
    assertThat(allowlist.violations(List.of(answer("GET /gap", 404))))
        .singleElement(STRING)
        .startsWith("GET /gap is under missingHeaders");
  }

  @Test
  void anEntryWithoutReasonIsRefused() {
    assertThatThrownBy(
            () ->
                AnonymousSurface.of(
                    Map.of(
                        "anonymous",
                        List.of(Map.of("operation", "GET /api/health", "reason", " ")))))
        .hasMessageContaining("reason");
    assertThatThrownBy(
            () ->
                AnonymousSurface.of(
                    Map.of("anonymous", List.of(Map.of("operation", "GET /api/health")))))
        .hasMessageContaining("reason");
  }

  @Test
  void anEntryWithoutMethodOrTwiceIsRefused() {
    assertThatThrownBy(
            () ->
                AnonymousSurface.of(
                    Map.of(
                        "anonymous", List.of(Map.of("operation", "/api/health", "reason", "x")))))
        .hasMessageContaining("operation");
    Map<String, String> entry = Map.of("operation", "GET /api/health", "reason", "x");
    assertThatThrownBy(() -> AnonymousSurface.of(Map.of("anonymous", List.of(entry, entry))))
        .hasMessageContaining("twice");
  }

  @Test
  void anUnknownSectionIsRefused() {
    assertThatThrownBy(() -> AnonymousSurface.of(Map.of("anonymus", List.of())))
        .hasMessageContaining("anonymous");
  }

  @Test
  void theCheckedInAllowlistLoads() {
    assertThat(AnonymousSurface.load().operations()).isNotEmpty();
  }

  private static Answer answer(String operation, int status) {
    int body = status == 204 ? 0 : 2;
    return new Answer(
        operation,
        operation.substring(operation.indexOf(' ') + 1),
        status,
        body,
        "nosniff",
        "DENY",
        null);
  }
}
