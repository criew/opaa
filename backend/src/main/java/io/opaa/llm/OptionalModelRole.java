package io.opaa.llm;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * What every optional model role - rerank, transcription, text recognition - shares: the state of
 * the role for a stored endpoint, the probe and the record of every outcome. A role adds only what
 * is its own: the operation's limit ({@link #operationAllows()}), the probe of its protocol and the
 * calls it offers.
 *
 * <p>Switch, address, model and key are the administration's ({@link OptionalRoleEndpoints},
 * ADR-0060, Entscheidung 10), read on every call, so a change takes effect without a restart.
 * {@link #currentStatus()} and {@link #statusFor} never probe; the state is kept current by {@link
 * #refresh()} and by every real call. A role is never fatal: a broken endpoint degrades the
 * feature, it does not stop the application.
 *
 * <p>The last known state is the installation's ({@link ModelRoleObservations}, ADR-0053,
 * Entscheidung 12): whichever instance probes or calls the endpoint records it, and every instance
 * decides on it. An observation counts only for the endpoint and {@link
 * OptionalRoleEndpoint#observedModel()} it was made for.
 */
public abstract class OptionalModelRole<E extends OptionalRoleEndpoint>
    implements OptionalRoleStatusProvider, OptionalRoleStatusResolver<E> {

  private static final Logger log = LoggerFactory.getLogger(OptionalModelRole.class);

  /**
   * A failed probe: a technical English message, and whether the endpoint did not answer in time.
   */
  public record ProbeFailure(String message, boolean timedOut) {}

  private final String observationKey;
  private final String name;
  private final Supplier<E> endpoints;
  private final ModelRoleObservations observations;

  /**
   * @param observationKey the role's key in {@link ModelRoleObservations}
   * @param name the role in log lines and diagnostics, e.g. {@code "rerank"}
   */
  protected OptionalModelRole(
      String observationKey,
      String name,
      Supplier<E> endpoints,
      ModelRoleObservations observations) {
    this.observationKey = observationKey;
    this.name = name;
    this.endpoints = endpoints;
    this.observations = observations;
  }

  /**
   * Whether the operation lets the role be switched on at all; while it does not, the role is
   * {@link OptionalRoleState#DISABLED} whatever is stored and contacts nobody.
   */
  protected boolean operationAllows() {
    return true;
  }

  /** Probes {@code endpoint} once; {@code null} when it answered. Blocking. */
  protected abstract ProbeFailure probe(E endpoint);

  /** The endpoint stored right now. */
  protected E endpoint() {
    return endpoints.get();
  }

  protected ModelRoleObservations observations() {
    return observations;
  }

  protected String observationKey() {
    return observationKey;
  }

  @Override
  public OptionalRoleStatus currentStatus() {
    return statusFor(endpoint());
  }

  /** The state of the role for {@code endpoint}, from the last known observation; never probes. */
  @Override
  public OptionalRoleStatus statusFor(E endpoint) {
    if (!operationAllows() || !endpoint.enabled()) {
      return OptionalRoleStatus.disabled();
    }
    if (ModelEndpointUri.containsCredentials(endpoint.baseUrl())) {
      return new OptionalRoleStatus(
          OptionalRoleState.UNCONFIGURED,
          null,
          emptyToNull(endpoint.model()),
          "the stored base URL carries credentials and was rejected",
          false,
          true);
    }
    if (!endpoint.bound()) {
      return new OptionalRoleStatus(
          OptionalRoleState.UNCONFIGURED,
          emptyToNull(endpoint.baseUrl()),
          emptyToNull(endpoint.model()),
          "no base URL and/or model is stored for the " + name + " role");
    }
    return observations
        .current(observationKey)
        .filter(known -> known.isFor(endpoint.baseUrl(), endpoint.observedModel()))
        .map(
            known ->
                known.reachable()
                    ? ready(endpoint)
                    : unreachable(endpoint, known.diagnostic(), known.timedOut()))
        .orElseGet(() -> unreachable(endpoint, "endpoint not probed yet", false));
  }

  /**
   * Probes the endpoint and records the result; contacts nothing while the role is not callable.
   * Blocking, and therefore never on a request path.
   */
  public OptionalRoleStatus refresh() {
    return refresh(endpoint());
  }

  protected OptionalRoleStatus refresh(E endpoint) {
    if (!callable(endpoint)) {
      return statusFor(endpoint);
    }
    ProbeFailure failure = probe(endpoint);
    OptionalRoleStatus probed =
        failure == null
            ? ready(endpoint)
            : unreachable(endpoint, failure.message(), failure.timedOut());
    record(endpoint, probed, true);
    return probed;
  }

  /** Whether the role may call its endpoint right now. Never blocks. */
  public boolean usable() {
    return currentStatus().state() == OptionalRoleState.READY;
  }

  /** Allowed by the operation, switched on, bound, and with an address the role may contact. */
  protected boolean callable(E endpoint) {
    return operationAllows()
        && endpoint.enabled()
        && endpoint.bound()
        && !ModelEndpointUri.containsCredentials(endpoint.baseUrl());
  }

  /**
   * Records an outcome; a store that cannot take it costs the record, never the call. {@code renew}
   * also renews the recorded time of an unchanged state.
   */
  protected void record(E endpoint, OptionalRoleStatus status, boolean renew) {
    try {
      observations.record(
          observationKey,
          new ModelRoleObservations.ModelRoleObservation(
              endpoint.baseUrl(),
              endpoint.observedModel(),
              status.state() == OptionalRoleState.READY,
              status.timedOut(),
              status.diagnostic(),
              null),
          renew);
    } catch (DataAccessException e) {
      log.warn("The state of the {} role could not be recorded: {}", name, e.getMessage());
    }
  }

  protected static OptionalRoleStatus ready(OptionalRoleEndpoint endpoint) {
    return new OptionalRoleStatus(
        OptionalRoleState.READY, endpoint.baseUrl(), emptyToNull(endpoint.model()), null);
  }

  protected static OptionalRoleStatus unreachable(
      OptionalRoleEndpoint endpoint, String diagnostic, boolean timedOut) {
    return new OptionalRoleStatus(
        OptionalRoleState.UNREACHABLE,
        endpoint.baseUrl(),
        emptyToNull(endpoint.model()),
        diagnostic,
        timedOut);
  }

  /**
   * A role's own probe thread. The application's default {@link TaskScheduler} has a single thread
   * shared by the indexing schedulers, and an endpoint that accepts a connection but never answers
   * would block it on every probe. One thread is enough: the probe schedule is the only recurring
   * task on it and runs with {@code fixedDelay}, so a hanging probe delays the next probe only.
   */
  public static TaskScheduler probeScheduler(String threadNamePrefix) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix(threadNamePrefix);
    scheduler.setDaemon(true);
    // A probe in flight must not hold up shutdown; its result would be discarded anyway.
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
    return scheduler;
  }

  private static String emptyToNull(String value) {
    return value.isEmpty() ? null : value;
  }
}
