package io.opaa.auth;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthConfigController.class)
@Import(TestSecurityConfig.class)
class AuthConfigControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AuthProperties authProperties;

  @Test
  void getAuthConfigReturnsModeFromProperties() throws Exception {
    when(authProperties.mode()).thenReturn("mock");

    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("mock"));
  }
}
