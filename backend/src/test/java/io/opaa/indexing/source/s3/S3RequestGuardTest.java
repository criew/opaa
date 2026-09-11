package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.sourceaccess.SourceRequestMeter;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;

/**
 * The parametrised guard shared by connector and upload storage (ADR-0030, Entscheidung 8): the
 * target policy runs before every attempt for every client, while counting, budget and throttle
 * bookkeeping exist only where a {@link SourceRequestMeter} is attached. No client is built here;
 * the interceptor is driven directly.
 */
class S3RequestGuardTest {

  private static SdkHttpRequest request(String host) {
    return SdkHttpRequest.builder()
        .protocol("http")
        .host(host)
        .port(9000)
        .method(SdkHttpMethod.GET)
        .encodedPath("/docs")
        .build();
  }

  private static Context.BeforeTransmission before(SdkHttpRequest request) {
    Context.BeforeTransmission context = mock(Context.BeforeTransmission.class);
    when(context.httpRequest()).thenReturn(request);
    return context;
  }

  private static Context.AfterTransmission after(SdkHttpRequest request, int status) {
    Context.AfterTransmission context = mock(Context.AfterTransmission.class);
    when(context.httpRequest()).thenReturn(request);
    when(context.httpResponse()).thenReturn(SdkHttpResponse.builder().statusCode(status).build());
    return context;
  }

  @Test
  void theTargetPolicyRunsBeforeEveryAttemptEvenWithoutAMeter() {
    S3RequestGuard guard =
        S3RequestGuard.targetCheckOnly(
            S3RequestGuard.TargetPolicy.hostOnly(new TargetAddressValidator(true, List.of())));

    assertThatThrownBy(
            () -> guard.beforeTransmission(before(request("127.0.0.1")), new ExecutionAttributes()))
        .isInstanceOf(NonRetryableException.class)
        .hasCauseInstanceOf(S3RequestGuard.TargetSignal.class)
        .satisfies(
            e -> {
              S3RequestGuard.TargetSignal signal = (S3RequestGuard.TargetSignal) e.getCause();
              assertThat(signal.unknownHost()).isFalse();
              assertThat(signal.getMessage()).contains("127.0.0.1");
            });
  }

  @Test
  void anUnresolvableHostIsSignalledAsUnknownNotAsBlocked() {
    S3RequestGuard guard =
        S3RequestGuard.targetCheckOnly(
            S3RequestGuard.TargetPolicy.hostOnly(new TargetAddressValidator(true, List.of())));

    assertThatThrownBy(
            () ->
                guard.beforeTransmission(
                    before(request("gibtsnicht.invalid")), new ExecutionAttributes()))
        .isInstanceOf(NonRetryableException.class)
        .satisfies(
            e -> assertThat(((S3RequestGuard.TargetSignal) e.getCause()).unknownHost()).isTrue());
  }

  @Test
  void aCustomPolicySeesTheWholeRequestNotJustTheHost() {
    List<String> seen = new ArrayList<>();
    S3RequestGuard.TargetPolicy policy =
        request -> {
          seen.add(request.protocol() + "://" + request.host() + ":" + request.port());
          if (request.port() != 9000) {
            throw new IOException("falscher Port");
          }
        };
    S3RequestGuard guard = S3RequestGuard.targetCheckOnly(policy);

    guard.beforeTransmission(before(request("minio")), new ExecutionAttributes());

    assertThat(seen).containsExactly("http://minio:9000");
  }

  @Test
  void withoutAMeterNothingIsCountedAndNoBudgetApplies() {
    List<SdkHttpRequest> observed = new ArrayList<>();
    S3RequestGuard guard = new S3RequestGuard(request -> {}, null, 0, observed::add);
    ExecutionAttributes attributes = new ExecutionAttributes();
    SdkHttpRequest request = request("minio");

    for (int i = 0; i < 5; i++) {
      guard.beforeTransmission(before(request), attributes);
      guard.afterTransmission(after(request, 503), attributes);
    }

    assertThat(observed).hasSize(5);
  }

  @Test
  void withAMeterEveryAttemptIsCountedAndTheBudgetEndsTheClient() {
    SourceRequestMeter meter = new SourceRequestMeter();
    S3RequestGuard guard = new S3RequestGuard(request -> {}, meter, 2, null);
    ExecutionAttributes attributes = new ExecutionAttributes();

    guard.beforeTransmission(before(request("minio")), attributes);
    guard.beforeTransmission(before(request("minio")), attributes);

    assertThatThrownBy(() -> guard.beforeTransmission(before(request("minio")), attributes))
        .isInstanceOf(NonRetryableException.class)
        .hasCauseInstanceOf(S3RequestGuard.BudgetSignal.class)
        .satisfies(
            e -> assertThat(((S3RequestGuard.BudgetSignal) e.getCause()).budget()).isEqualTo(2));
    assertThat(meter.requests()).isEqualTo(2);
    assertThat(guard.requestBudget()).isEqualTo(2);
  }

  @Test
  void aZeroBudgetWithAMeterIsUnbounded() {
    SourceRequestMeter meter = new SourceRequestMeter();
    S3RequestGuard guard = new S3RequestGuard(request -> {}, meter, 0, null);
    ExecutionAttributes attributes = new ExecutionAttributes();

    for (int i = 0; i < 50; i++) {
      guard.beforeTransmission(before(request("minio")), attributes);
    }

    assertThat(meter.requests()).isEqualTo(50);
  }

  @Test
  void aThrottledAnswerIsCountedAndItsWaitMeasuredOnTheNextAttempt() throws Exception {
    SourceRequestMeter meter = new SourceRequestMeter();
    S3RequestGuard guard = new S3RequestGuard(request -> {}, meter, 0, null);
    ExecutionAttributes attributes = new ExecutionAttributes();
    SdkHttpRequest request = request("minio");

    guard.beforeTransmission(before(request), attributes);
    guard.afterTransmission(after(request, 429), attributes);
    Thread.sleep(20);
    guard.beforeTransmission(before(request), attributes);
    guard.afterTransmission(after(request, 200), attributes);

    assertThat(meter.throttles()).isEqualTo(1);
    assertThat(meter.throttledTime()).isGreaterThanOrEqualTo(java.time.Duration.ofMillis(15));
  }

  @Test
  void aBudgetWithoutAMeterIsAWiringError() {
    assertThatThrownBy(() -> new S3RequestGuard(request -> {}, null, 5, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("meter");
  }
}
