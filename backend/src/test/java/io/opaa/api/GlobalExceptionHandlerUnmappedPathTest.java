package io.opaa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #456: a request to a path no controller serves falls through Spring's static resource handling,
 * which throws {@code NoResourceFoundException} - previously uncaught by {@link
 * GlobalExceptionHandler}, so it fell through to {@code handleGenericException}'s {@code 500}
 * instead of the {@code 404} an unmapped route should produce. Deliberately hits a path that
 * neither {@link HealthController} nor any other controller in this slice maps, so the exception
 * actually originates from the resource handler rather than from a controller method - a test that
 * only checked the status code against a controller-mapped path would not have caught the bug (see
 * AGENTS.md, "Reproduktionsnachweis").
 */
@WebMvcTest(HealthController.class)
@Import(TestSecurityConfig.class)
class GlobalExceptionHandlerUnmappedPathTest {

  @Autowired private MockMvc mockMvc;

  // TestSecurityConfig's UserProvisioningFilter needs a UserService bean even though this
  // unauthenticated slice never calls it.
  @MockitoBean private UserService userService;

  @Test
  void unmappedPathReturnsNotFoundInsteadOfInternalServerError() throws Exception {
    mockMvc
        .perform(get("/api/v1/gibtesnicht"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Die angeforderte Ressource wurde nicht gefunden"));
  }
}
