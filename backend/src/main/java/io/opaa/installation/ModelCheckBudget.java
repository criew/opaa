package io.opaa.installation;

import io.opaa.common.TooManyRequestsException;
import io.opaa.ratelimit.RateLimitService;
import java.util.ArrayList;
import java.util.List;

/**
 * The budget per instance (ADR-0053) for the probes of the model steps, one for all four steps and
 * all clients together, apart from the budget of model discoveries and the API's own. Every probed
 * model step takes one request; a refused call keeps none.
 */
final class ModelCheckBudget {

  static final int MAX_MODEL_CHECKS = 30;
  static final int WINDOW_SECONDS = 600;

  /** The code of a refusal by this budget, apart from the per-address budget of the API. */
  static final String LIMIT_CODE = "MODEL_CHECK_LIMIT";

  private static final String LIMIT_MESSAGE =
      "Zu viele Prüfungen der Modelle. Bitte später erneut versuchen.";
  private static final String BUDGET_KEY = "installation-model-check";

  private final RateLimitService budget = new RateLimitService(MAX_MODEL_CHECKS, WINDOW_SECONDS);

  /** Requests taken from the budget, which a caller refused for another reason gives back. */
  final class Grant {

    private final List<RateLimitService.Decision> decisions;

    private Grant(List<RateLimitService.Decision> decisions) {
      this.decisions = List.copyOf(decisions);
    }

    void release() {
      decisions.forEach(decision -> budget.release(BUDGET_KEY, decision));
    }
  }

  /**
   * Takes one request for every model step among {@code steps}, all or none.
   *
   * @throws TooManyRequestsException past the budget
   */
  Grant acquire(List<InstallationStep> steps) {
    List<RateLimitService.Decision> granted = new ArrayList<>();
    for (InstallationStep step : steps) {
      if (!InstallationModelDiscovery.MODEL_STEPS.contains(step)) {
        continue;
      }
      RateLimitService.Decision decision = budget.tryAcquire(BUDGET_KEY);
      if (!decision.allowed()) {
        new Grant(granted).release();
        throw new TooManyRequestsException(LIMIT_MESSAGE, decision.retryAfterSeconds(), LIMIT_CODE);
      }
      granted.add(decision);
    }
    return new Grant(granted);
  }
}
