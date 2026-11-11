package io.opaa.indexing.spi;

import io.opaa.indexing.job.RequestBudgetExhaustedException;
import io.opaa.security.TargetAddressValidator.TargetAddressBlockedException;
import io.opaa.sourceaccess.RateLimitListener;
import io.opaa.sourceaccess.SourceRequestMeter;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Bounds one run's requests on its {@link SourceRequestMeter}: at most {@code requestBudget}
 * requests ({@code 0}: unbounded), at most {@code maxThrottleWait} of waiting on throttled answers
 * in total ({@code null} or zero: unbounded) and at most {@code maxHosts} distinct host names
 * ({@code 0}: unbounded). Every request is charged before it leaves ({@link #charge}); as the
 * {@link RateLimitListener} of a fetch it charges every attempt and refuses a wait that would cross
 * the cap before that wait is slept - each with a {@link RequestBudgetExhaustedException} - and
 * refuses a hop to a further host with a {@link HostLimitExceededException}, which ends only that
 * fetch.
 */
public final class RequestBudget implements RateLimitListener {

  private final SourceRequestMeter meter;
  private final int requestBudget;
  private final Duration maxThrottleWait;
  private final int maxHosts;
  private final Set<String> hosts = new HashSet<>();

  public RequestBudget(SourceRequestMeter meter, int requestBudget, Duration maxThrottleWait) {
    this(meter, requestBudget, maxThrottleWait, 0);
  }

  public RequestBudget(
      SourceRequestMeter meter, int requestBudget, Duration maxThrottleWait, int maxHosts) {
    this.meter = Objects.requireNonNull(meter, "meter");
    this.requestBudget = Math.max(0, requestBudget);
    this.maxThrottleWait =
        maxThrottleWait == null || maxThrottleWait.isNegative() || maxThrottleWait.isZero()
            ? null
            : maxThrottleWait;
    this.maxHosts = Math.max(0, maxHosts);
  }

  /** A fresh meter under the run bounds of {@code policy}. */
  public static RequestBudget forRun(SourceRequestPolicy policy) {
    return new RequestBudget(
        new SourceRequestMeter(),
        policy.requestBudgetPerRun(),
        policy.maxRateLimitWaitPerRun(),
        policy.maxHostsPerRun());
  }

  /** A fresh meter without bounds - counting only. */
  public static RequestBudget unbounded() {
    return new RequestBudget(new SourceRequestMeter(), 0, null);
  }

  public SourceRequestMeter meter() {
    return meter;
  }

  public int requestBudget() {
    return requestBudget;
  }

  /** Counts one request; refuses it when the budget is spent, leaving the count as it is. */
  public void charge() {
    if (!meter.recordRequestWithin(requestBudget)) {
      throw RequestBudgetExhaustedException.requests(requestBudget);
    }
  }

  /**
   * Admits a hop to a host this run already contacted, or to a new one while fewer than {@code
   * maxHosts} were; names compare case-insensitively and without a trailing root dot. A refused hop
   * is never checked or contacted, so a run pins at most {@code maxHosts} names (ADR-0063).
   */
  @Override
  public void connecting(URI target) throws HostLimitExceededException {
    String host = target.getHost();
    if (maxHosts == 0 || host == null) {
      return;
    }
    String name = host.toLowerCase(Locale.ROOT);
    String normalised = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    synchronized (hosts) {
      if (hosts.contains(normalised)) {
        return;
      }
      if (hosts.size() >= maxHosts) {
        throw new HostLimitExceededException(maxHosts);
      }
      hosts.add(normalised);
    }
  }

  @Override
  public void throttled(int statusCode, Duration wait) {
    if (maxThrottleWait == null) {
      meter.recordThrottle(wait);
      return;
    }
    if (!meter.recordThrottleWithin(wait, maxThrottleWait)) {
      throw RequestBudgetExhaustedException.throttleWait(maxThrottleWait);
    }
  }

  @Override
  public void sending() {
    charge();
  }

  /**
   * A hop refused because its run already contacted the most distinct hosts it may; a blocked
   * target for every caller, so the item is skipped and the run goes on. The message is German and
   * user-facing and names no host.
   */
  public static final class HostLimitExceededException extends TargetAddressBlockedException {
    HostLimitExceededException(int maxHosts) {
      super(
          "Der Lauf hat bereits "
              + maxHosts
              + " verschiedene Hosts angefragt, die Höchstzahl je Lauf; weitere Hosts fragt er"
              + " nicht an (OPAA_INDEXING_HTTP_MAX_HOSTS_PER_RUN).");
    }
  }
}
