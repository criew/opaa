package io.opaa.indexing.source.s3;

import io.opaa.sourceaccess.SourceRequestMeter;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;

/**
 * Sees every wire attempt of an {@link S3SdkClient}: validates the target the signed request goes
 * to, and - when a {@link SourceRequestMeter} is attached - counts the attempt, charges the request
 * budget and measures the wait after a throttled answer. The target check belongs to every client
 * (ADR-0027, Entscheidung 8; ADR-0030, Entscheidung 8); meter and budget only to a connector run,
 * so a client without a meter is never counted and never budgeted. A budget of {@code 0} is
 * unbounded. Refusals are {@link NonRetryableException}s the SDK does not retry.
 */
public final class S3RequestGuard implements ExecutionInterceptor {

  private static final Logger log = LoggerFactory.getLogger(S3RequestGuard.class);

  private static final ExecutionAttribute<Instant> THROTTLED_AT =
      new ExecutionAttribute<>("opaa.s3.throttledAt");

  /** Decides before every wire attempt whether the signed request may leave. */
  @FunctionalInterface
  public interface TargetPolicy {

    /**
     * @throws TargetAddressValidator.UnknownTargetHostException when the host does not resolve
     * @throws IOException when the target is refused; the message is user-facing
     */
    void validate(SdkHttpRequest request) throws IOException;

    /** The connector's policy: the host of every request passes {@code validator}. */
    static TargetPolicy hostOnly(TargetAddressValidator validator) {
      return request -> validator.validateHost(request.host());
    }
  }

  private final TargetPolicy targetPolicy;
  private final SourceRequestMeter meter;
  private final int requestBudget;
  private final Consumer<SdkHttpRequest> requestObserver;

  /**
   * @param meter {@code null} for a client whose requests are neither counted nor budgeted
   * @param requestBudget wire attempts (retries included) before the client refuses; {@code 0} is
   *     unbounded, and a positive value needs a meter to charge
   * @param requestObserver sees every signed request before transmission; {@code null} for none
   */
  public S3RequestGuard(
      TargetPolicy targetPolicy,
      SourceRequestMeter meter,
      int requestBudget,
      Consumer<SdkHttpRequest> requestObserver) {
    if (targetPolicy == null) {
      throw new IllegalArgumentException("a target policy is required");
    }
    if (requestBudget < 0) {
      throw new IllegalArgumentException(
          "requestBudget must not be negative, got " + requestBudget);
    }
    if (requestBudget > 0 && meter == null) {
      throw new IllegalArgumentException("a request budget needs a meter to charge");
    }
    this.targetPolicy = targetPolicy;
    this.meter = meter;
    this.requestBudget = requestBudget;
    this.requestObserver = requestObserver;
  }

  /** A guard for a client that is only target-checked - no meter, no budget, no observer. */
  public static S3RequestGuard targetCheckOnly(TargetPolicy targetPolicy) {
    return new S3RequestGuard(targetPolicy, null, 0, null);
  }

  int requestBudget() {
    return requestBudget;
  }

  @Override
  public void beforeTransmission(
      Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
    if (meter != null) {
      Instant throttledAt = executionAttributes.getAttribute(THROTTLED_AT);
      if (throttledAt != null && !Instant.EPOCH.equals(throttledAt)) {
        meter.recordThrottleWait(Duration.between(throttledAt, Instant.now()));
        executionAttributes.putAttribute(THROTTLED_AT, Instant.EPOCH);
      }
    }
    SdkHttpRequest request = context.httpRequest();
    try {
      targetPolicy.validate(request);
    } catch (TargetAddressValidator.UnknownTargetHostException e) {
      throw NonRetryableException.create(
          "target unresolved", new TargetSignal(e.getMessage(), true));
    } catch (IOException e) {
      throw NonRetryableException.create("target blocked", new TargetSignal(e.getMessage(), false));
    }
    if (meter != null && !meter.recordRequestWithin(requestBudget)) {
      throw NonRetryableException.create(
          "request budget exhausted", new BudgetSignal(requestBudget));
    }
    if (requestObserver != null) {
      requestObserver.accept(request);
    }
  }

  @Override
  public void afterTransmission(
      Context.AfterTransmission context, ExecutionAttributes executionAttributes) {
    int status = context.httpResponse().statusCode();
    if (status == 429 || status == 503) {
      if (meter != null) {
        meter.recordThrottle();
        executionAttributes.putAttribute(THROTTLED_AT, Instant.now());
      }
      log.debug(
          "S3 endpoint {} answered HTTP {} to {} - retrying with backoff ({} so far)",
          context.httpRequest().host(),
          status,
          context.httpRequest().encodedPath(),
          meter == null ? "uncounted" : meter.throttles());
    }
  }

  /** Raised from the guard when the run's request budget is spent. */
  static final class BudgetSignal extends RuntimeException {
    private final int budget;

    BudgetSignal(int budget) {
      super("request budget of " + budget + " exhausted");
      this.budget = budget;
    }

    int budget() {
      return budget;
    }
  }

  /** Raised from the guard when a request's target fails the policy. */
  static final class TargetSignal extends RuntimeException {
    private final boolean unknownHost;

    TargetSignal(String message, boolean unknownHost) {
      super(message);
      this.unknownHost = unknownHost;
    }

    boolean unknownHost() {
      return unknownHost;
    }
  }
}
