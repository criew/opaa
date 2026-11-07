package io.opaa.auth.web;

import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.QuickTourHint;
import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Marks the hint of the quick tour done for the caller's own account and for no other. Registered
 * only while the quick tour is switched on (ADR-0044): switched off, the route does not exist and
 * nothing is written.
 */
@RestController
@ConditionalOnFeature(Feature.QUICK_TOUR)
public class QuickTourHintController {

  private final QuickTourHint quickTourHint;

  public QuickTourHintController(QuickTourHint quickTourHint) {
    this.quickTourHint = quickTourHint;
  }

  @PutMapping("/api/v1/me/quick-tour-hint")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markDone(@Caller CurrentUser caller) {
    quickTourHint.markDone(caller.id());
  }
}
