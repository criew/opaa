package io.opaa.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.test.OpaaMockMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Reproduction proof for #884: before the {@link CurrentUser} snapshot, every controller
 * independently reconstructed the caller from the {@code Jwt} principal via {@link
 * UserService#findOrCreateUser}, so a single HTTP request whose controller handler called into more
 * than one service could re-run {@link UserRepository#findBySubjectAndIssuer} several times. {@link
 * UserProvisioningFilter} now performs that lookup exactly once per request and hands the result to
 * every controller/service via {@link CurrentUser} - proven here by counting real repository
 * invocations across the full authenticated {@code dev} chain.
 *
 * <p>Own {@link MockitoSpyBean} forces its own Spring context (see {@link OpaaMockMvcTest}'s
 * Javadoc) - a shared context would let concurrent/prior tests' requests through the same spy
 * inflate the count this test asserts on.
 */
@OpaaMockMvcTest
class UserProvisioningLoadCountIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @MockitoSpyBean private UserRepository userRepository;

  private RequestPostProcessor devUser() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-user");
      return request;
    };
  }

  @Test
  void oneAuthenticatedRequestLoadsTheCallerExactlyOnce() throws Exception {
    mockMvc.perform(get("/api/v1/me/groups").with(devUser())).andExpect(status().isOk());

    verify(userRepository, times(1)).findBySubjectAndIssuer(anyString(), anyString());
    // The old per-controller currentUser(Jwt) helpers this refactor removed re-derived the caller
    // via findById on the just-loaded id, not only findBySubjectAndIssuer - both loads must be
    // gone.
    verify(userRepository, never()).findById(any());
  }
}
