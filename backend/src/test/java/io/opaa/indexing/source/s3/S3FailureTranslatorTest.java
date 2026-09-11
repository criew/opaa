package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.source.RequestBudgetExhaustedException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The shared failure translation (ADR-0030, Entscheidung 8) on hand-built SDK exceptions: the
 * operation decides what a {@code 403} and a {@code 404} mean - the write operations of the upload
 * storage included - the owning side's allowlist hint is appended to a refused target, and the
 * bounds of the message (timeout, retries) are the translator's own. The end-to-end mapping of
 * every failure a server can answer stays with {@code AwsSdkS3ObjectStoreTest}.
 */
class S3FailureTranslatorTest {

  private static final String SECRET = "geheimes-token-4711";

  private final S3FailureTranslator translator =
      new S3FailureTranslator(Duration.ofSeconds(7), 2, "Hinweis: OPAA_TEST_ALLOWLIST");

  private static S3Exception service(int status, String code) {
    return (S3Exception)
        S3Exception.builder()
            .statusCode(status)
            .message("upstream text with " + SECRET)
            .awsErrorDetails(
                AwsErrorDetails.builder()
                    .errorCode(code)
                    .errorMessage("upstream text with " + SECRET)
                    .build())
            .build();
  }

  @Test
  void aForbiddenAnswerNamesThePermissionOfTheOperation() {
    assertThat(translator.translate(S3Operation.PUT_OBJECT, "b", "k", service(403, "AccessDenied")))
        .isInstanceOf(S3AccessException.WriteForbidden.class)
        .hasMessageContaining("s3:PutObject")
        .hasMessageContaining("b/k");
    assertThat(
            translator.translate(S3Operation.DELETE_OBJECT, "b", "k", service(403, "AccessDenied")))
        .isInstanceOf(S3AccessException.WriteForbidden.class)
        .hasMessageContaining("s3:DeleteObject");
    assertThat(translator.translate(S3Operation.GET_OBJECT, "b", "k", service(403, "AccessDenied")))
        .isInstanceOf(S3AccessException.ReadForbidden.class)
        .hasMessageContaining("s3:GetObject");
    assertThat(
            translator.translate(S3Operation.LIST_OBJECTS, "b", null, service(403, "AccessDenied")))
        .isInstanceOf(S3AccessException.ListForbidden.class)
        .hasMessageContaining("s3:ListBucket");
  }

  @Test
  void aNotFoundAnswerNamesWhatIsMissingForTheOperation() {
    assertThat(translator.translate(S3Operation.PUT_OBJECT, "b", "k", service(404, null)))
        .isInstanceOf(S3AccessException.BucketNotFound.class);
    assertThat(translator.translate(S3Operation.DELETE_OBJECT, "b", "k", service(404, null)))
        .isInstanceOf(S3AccessException.ObjectNotFound.class);
    assertThat(translator.translate(S3Operation.HEAD_BUCKET, "b", null, service(404, null)))
        .isInstanceOf(S3AccessException.BucketNotFound.class);
    assertThat(
            translator.translate(
                S3Operation.HEAD_OBJECT,
                "b",
                "k",
                NoSuchKeyException.builder().statusCode(404).message(SECRET).build()))
        .isInstanceOf(S3AccessException.ObjectNotFound.class);
  }

  @Test
  void aRefusedTargetCarriesTheOwningSidesAllowlistHint() {
    NonRetryableException blocked =
        NonRetryableException.create(
            "target blocked", new S3RequestGuard.TargetSignal("Adresse gesperrt.", false));
    NonRetryableException unknown =
        NonRetryableException.create(
            "target unresolved", new S3RequestGuard.TargetSignal("Host unbekannt.", true));

    assertThat(translator.translate(S3Operation.GET_OBJECT, "b", "k", blocked))
        .isInstanceOf(S3AccessException.TargetBlocked.class)
        .hasMessage("Adresse gesperrt. Hinweis: OPAA_TEST_ALLOWLIST");
    assertThat(translator.translate(S3Operation.GET_OBJECT, "b", "k", unknown))
        .isInstanceOf(S3AccessException.Unreachable.class)
        .hasMessageContaining("Host unbekannt.")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("OPAA_TEST_ALLOWLIST"));
  }

  @Test
  void theConnectorsTranslatorNamesTheIndexingAllowlist() {
    S3FailureTranslator connector = S3FailureTranslator.forConnector(S3Properties.defaults());
    NonRetryableException blocked =
        NonRetryableException.create(
            "target blocked", new S3RequestGuard.TargetSignal("Adresse gesperrt.", false));

    assertThat(connector.translate(S3Operation.LIST_OBJECTS, "b", null, blocked))
        .hasMessageContaining("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
  }

  @Test
  void timeoutsAndThrottlingReportTheTranslatorsOwnBounds() {
    assertThat(
            translator.translate(
                S3Operation.GET_OBJECT,
                "b",
                "k",
                ApiCallAttemptTimeoutException.builder().message("timed out").build()))
        .isInstanceOf(S3AccessException.Unreachable.class)
        .hasMessageContaining("7 Sekunden");
    assertThat(translator.translate(S3Operation.GET_OBJECT, "b", "k", service(503, "SlowDown")))
        .isInstanceOf(S3AccessException.RateLimited.class)
        .hasMessageContaining("2 Wiederholungen");
  }

  @Test
  void callTranslatesInterruptionAndASpentBudget() {
    assertThatThrownBy(
            () ->
                translator.call(
                    S3Operation.HEAD_OBJECT,
                    "b",
                    "k",
                    () -> {
                      throw AbortedException.builder().message("aborted").build();
                    }))
        .isInstanceOf(InterruptedException.class);
    assertThat(Thread.interrupted()).as("the interrupt flag is restored").isTrue();

    assertThatThrownBy(
            () ->
                translator.call(
                    S3Operation.HEAD_OBJECT,
                    "b",
                    "k",
                    () -> {
                      throw NonRetryableException.create(
                          "budget", new S3RequestGuard.BudgetSignal(3));
                    }))
        .isInstanceOf(RequestBudgetExhaustedException.class)
        .hasMessageContaining("3 Anfragen");
  }

  @Test
  void noTranslatedFailureCarriesTheUpstreamTextOrACause() {
    List<Throwable> failures =
        List.of(
            service(403, "AccessDenied"),
            service(403, "InvalidAccessKeyId"),
            service(500, "InternalError"),
            SdkClientException.builder()
                .message("client " + SECRET)
                .cause(new ConnectException("refused " + SECRET))
                .build(),
            new IllegalStateException("odd " + SECRET));

    for (Throwable failure : failures) {
      S3AccessException translated =
          translator.translate(S3Operation.PUT_OBJECT, "b", "k", failure);
      assertThat(translated.getMessage()).doesNotContain(SECRET);
      assertThat(translated.getCause()).isNull();
    }
  }
}
