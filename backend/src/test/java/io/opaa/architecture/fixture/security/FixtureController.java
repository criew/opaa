package io.opaa.architecture.fixture.security;

import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two handlers without a check - one has none, one lets everyone in - and two with one; abstract,
 * so no test context registers it.
 */
@RestController
@RequestMapping("/fixture/items")
public abstract class FixtureController {

  @GetMapping("/{itemId:[0-9]+}")
  public String open(String itemId) {
    return itemId;
  }

  @GetMapping
  public String own(@Caller CurrentUser caller) {
    return caller.displayName();
  }

  @GetMapping("/public")
  @PreAuthorize("permitAll()")
  public String everyone() {
    return "";
  }

  @PostMapping
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  public String administered() {
    return "";
  }
}
