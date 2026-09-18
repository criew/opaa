package io.opaa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.UserService;
import io.opaa.auth.local.LocalAuthRateLimiter;
import io.opaa.auth.local.LocalSelfServiceController;
import io.opaa.auth.local.LocalSelfServiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #1707: a wrong HTTP method and a wrong {@code Content-Type} are caller errors, but {@link
 * GlobalExceptionHandler}'s catch-all claimed both before Spring's {@code
 * DefaultHandlerExceptionResolver} could render them, so they arrived as {@code 500}. Deliberately
 * driven through {@link MockMvc} against a path this slice really maps: the method mismatch is
 * raised by handler mapping and the media type while the body is read, so neither exception exists
 * when a controller method is called directly. {@code POST /api/v1/auth/local/register} is the
 * endpoint both cases were measured on.
 */
@WebMvcTest(LocalSelfServiceController.class)
@ActiveProfiles("oidc")
@Import(TestSecurityConfig.class)
class GlobalExceptionHandlerRequestShapeTest {

  private static final String REGISTER_PATH = "/api/v1/auth/local/register";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private LocalSelfServiceService selfServiceService;
  @MockitoBean private LocalAuthRateLimiter rateLimiter;

  // TestSecurityConfig's UserProvisioningFilter needs a UserService bean even though none of these
  // requests ever reaches a controller method.
  @MockitoBean private UserService userService;

  @Test
  void unsupportedMethodReturnsMethodNotAllowedWithAllowHeader() throws Exception {
    mockMvc
        .perform(get(REGISTER_PATH))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", "POST"))
        .andExpect(jsonPath("$.status").value(405))
        .andExpect(
            jsonPath("$.error")
                .value("Die HTTP-Methode wird für diese Ressource nicht unterstützt"));
  }

  @Test
  void unsupportedContentTypeReturnsUnsupportedMediaType() throws Exception {
    mockMvc
        .perform(post(REGISTER_PATH).contentType(MediaType.TEXT_PLAIN).content("kein JSON"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.status").value(415))
        .andExpect(jsonPath("$.error").value("Der Inhaltstyp der Anfrage wird nicht unterstützt"));
  }

  /** A body without any {@code Content-Type} is read as {@code application/octet-stream}. */
  @Test
  void missingContentTypeReturnsUnsupportedMediaType() throws Exception {
    mockMvc
        .perform(post(REGISTER_PATH).content("{\"email\":\"a@example.com\"}"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.status").value(415))
        .andExpect(jsonPath("$.error").value("Der Inhaltstyp der Anfrage wird nicht unterstützt"));
  }

  @Test
  void formEncodedBodyReturnsUnsupportedMediaType() throws Exception {
    mockMvc
        .perform(
            post(REGISTER_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("email=a@example.com"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.status").value(415))
        .andExpect(jsonPath("$.error").value("Der Inhaltstyp der Anfrage wird nicht unterstützt"));
  }
}
