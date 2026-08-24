package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.opaa.api.MeController;
import io.opaa.group.GroupService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Security regression guard: with {@link CurrentUserArgumentResolver} absent from the resolver
 * chain (simulated here via {@code standaloneSetup}, which registers only Spring's built-in
 * resolvers), a {@code ?id=...&systemRole=SYSTEM_ADMIN} query string must never bind an attacker-
 * chosen {@link CurrentUser} through Spring MVC's catch-all {@code ModelAttributeMethodProcessor} -
 * it must fail the request instead, and {@link GroupService#listMyGroups} must never run with a
 * caller-controlled identity. {@link CurrentUser}'s reflection-guard constructor (see its Javadoc)
 * is what actually enforces this; {@code @Caller} on the parameter alone is not sufficient defense
 * in depth, since it only prevents this specific resolver from erroneously claiming an unannotated
 * parameter - it does nothing once the annotated parameter falls through to another resolver.
 */
class CurrentUserFailClosedTest {

  @Test
  void queryParametersNeverBindAnAttackerChosenCurrentUserWithoutTheDedicatedResolver() {
    GroupService groupService = mock(GroupService.class);
    MeController controller = new MeController(groupService);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    // No GlobalExceptionHandler wired in this standalone setup - MockMvc rethrows the failure
    // exactly as it occurred, unwrapped exception-handling would otherwise turn it into. Either
    // way, the request never completes with 200 and an attacker-controlled identity.
    assertThatThrownBy(
            () ->
                mockMvc.perform(
                    get("/api/v1/me/groups")
                        .param("id", "11111111-1111-1111-1111-111111111111")
                        .param("organizationId", "22222222-2222-2222-2222-222222222222")
                        .param("systemRole", "SYSTEM_ADMIN")
                        .param("displayName", "Attacker")))
        .hasRootCauseInstanceOf(UnsupportedOperationException.class);

    // Never reached: had a CurrentUser been bound, groupService.listMyGroups(...) would have run.
    verifyNoInteractions(groupService);
  }
}
