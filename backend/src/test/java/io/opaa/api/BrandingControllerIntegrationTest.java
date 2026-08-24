package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.branding.BrandingDefaults;
import io.opaa.branding.BrandingLogoValidator;
import io.opaa.test.OpaaMockMvcTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * #582's acceptance criteria at the HTTP boundary, against the real {@code dev} security chain
 * ({@link DevAuthFilter}, {@code UserProvisioningFilter}, the real {@code @PreAuthorize} on {@code
 * SystemBrandingController}) and a real Postgres - the same shape {@link
 * AuditControllerAuthorizationIntegrationTest} and {@link
 * LibraryControllerCredentialsIntegrationTest} already use, and for the same reason: a
 * {@code @WebMvcTest} slice would have to stub the very authorization decision the criterion is
 * about ("PUT verweigert Nicht-Administratoren (403)").
 *
 * <p>Deliberately one class for every HTTP-level concern of this feature rather than one per
 * endpoint. Uses the shared {@link TestcontainersConfiguration} rather than declaring its own
 * {@code @Container}/{@code @DynamicPropertySource} (issue #497, measure 5): a per-class
 * {@code @DynamicPropertySource} customizer keeps Spring's context cache from recognizing two
 * otherwise identical {@code @SpringBootTest} classes as the same context, so every such class used
 * to get its own ApplicationContext and its own Postgres container - the accumulation {@code
 * build.gradle.kts}'s heap-ceiling comment describes. This class, {@link
 * AuditControllerAuthorizationIntegrationTest} and {@link
 * LibraryControllerCredentialsIntegrationTest} now carry the identical
 * {@code @SpringBootTest}/{@code @AutoConfigureMockMvc}/{@code @Import(TestcontainersConfiguration.class)}/{@code @ActiveProfiles("dev")}
 * signature and therefore share one cached context and one container. Everything that does not need
 * MockMvc lives in {@code BrandingSettingsServiceIntegrationTest}, which shares the other, {@code
 * {"local", "dev"}}-profiled context group instead.
 */
@OpaaMockMvcTest
class BrandingControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void resetBranding() {
    jdbcTemplate.update(
        "UPDATE branding_settings SET product_name = NULL, claim = NULL, primary_color = NULL,"
            + " default_color_scheme = NULL, logo_content = NULL, logo_content_type = NULL,"
            + " logo_version = NULL, logo_updated_at = NULL, updated_at = now() WHERE id = 1");
  }

  @Test
  void anUnconfiguredDeploymentAnswersWithTheOpaaStandard() throws Exception {
    mockMvc
        .perform(get("/api/v1/branding").with(devUser("dev-user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.productName").value(BrandingDefaults.PRODUCT_NAME))
        .andExpect(jsonPath("$.claim").value(BrandingDefaults.CLAIM))
        .andExpect(jsonPath("$.primaryColor").value(BrandingDefaults.PRIMARY_COLOR))
        .andExpect(jsonPath("$.defaultColorScheme").value(BrandingDefaults.COLOR_SCHEME.name()))
        .andExpect(jsonPath("$.logoUrl").doesNotExist());
  }

  /**
   * The read endpoint is deliberately not administrator-only, because every rendered page needs it
   * (#582). That it is reachable with no credentials at all (#583, for the sign-in page) is a
   * question this profile cannot answer - {@link DevAuthFilter} authenticates every request as the
   * configured default user, so a header-less request here is not an anonymous one; {@code
   * BrandingPublicAccessTest} asserts that against the {@code oidc} chain instead.
   */
  @Test
  void readingBrandingIsOpenToAPlainUserNotOnlyToAdministrators() throws Exception {
    mockMvc.perform(get("/api/v1/branding").with(devUser("dev-user"))).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/branding").with(devUser("dev-admin"))).andExpect(status().isOk());
  }

  @Test
  void aPlainUserMayNotChangeBranding() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/system/branding")
                .with(devUser("dev-user"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productName\":\"Landesamt-Assistent\"}"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(get("/api/v1/branding").with(devUser("dev-user")))
        .andExpect(jsonPath("$.productName").value(BrandingDefaults.PRODUCT_NAME));
  }

  @Test
  void aPlainUserMayNeitherUploadNorRemoveTheLogo() throws Exception {
    mockMvc
        .perform(
            multipart(HttpMethod.PUT, "/api/v1/system/branding/logo")
                .file(logoPart(png(120, 40)))
                .with(devUser("dev-user")))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(delete("/api/v1/system/branding/logo").with(devUser("dev-user")))
        .andExpect(status().isForbidden());
  }

  @Test
  void anAdministratorChangesBrandingAndEveryUserSeesItImmediately() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/system/branding")
                .with(devUser("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"productName\":\"Landesamt-Assistent\",\"claim\":\"Kurz und klar\","
                        + "\"primaryColor\":\"#7A1FA2\",\"defaultColorScheme\":\"DARK\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.productName").value("Landesamt-Assistent"));

    mockMvc
        .perform(get("/api/v1/branding").with(devUser("dev-user")))
        .andExpect(jsonPath("$.productName").value("Landesamt-Assistent"))
        .andExpect(jsonPath("$.claim").value("Kurz und klar"))
        .andExpect(jsonPath("$.primaryColor").value("#7A1FA2"))
        .andExpect(jsonPath("$.defaultColorScheme").value("DARK"));
  }

  @Test
  void anInvalidColourIsRejectedWithAGermanMessage() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/system/branding")
                .with(devUser("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"primaryColor\":\"blau\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Primärfarbe")));
  }

  @Test
  void anUnknownColourSchemeIsRejected() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/system/branding")
                .with(devUser("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaultColorScheme\":\"NEONGRUEN\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anUploadedLogoIsServedBackUnderTheDetectedTypeWithSniffingDisabled() throws Exception {
    byte[] content = png(120, 40);

    String response =
        mockMvc
            .perform(
                multipart(HttpMethod.PUT, "/api/v1/system/branding/logo")
                    .file(logoPart(content))
                    .with(devUser("dev-admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logoContentType").value(BrandingLogoValidator.PNG_MIME_TYPE))
            .andExpect(jsonPath("$.logoUrl").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).contains("/api/v1/branding/logo?v=");

    mockMvc
        .perform(get("/api/v1/branding/logo").with(devUser("dev-user")))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.IMAGE_PNG))
        .andExpect(content().bytes(content))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Disposition", "inline; filename=\"logo\""))
        .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
        .andExpect(header().exists("ETag"));
  }

  /**
   * The point of the whole format restriction: a file uploaded under an image content type is not
   * an image just because the request said so. The declared part type here is {@code image/png};
   * the bytes are an SVG carrying a script, and that is what decides.
   */
  @Test
  void anSvgDisguisedAsAPngIsRejected() throws Exception {
    byte[] svg =
        ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">"
                + "<script>alert(1)</script></svg>")
            .getBytes(StandardCharsets.UTF_8);

    mockMvc
        .perform(
            multipart(HttpMethod.PUT, "/api/v1/system/branding/logo")
                .file(new MockMultipartFile("file", "logo.png", MediaType.IMAGE_PNG_VALUE, svg))
                .with(devUser("dev-admin")))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(get("/api/v1/branding/logo").with(devUser("dev-user")))
        .andExpect(status().isNotFound());
  }

  /**
   * The container's own multipart limit is the 50 MiB document-upload one, so an oversized logo
   * gets past Spring entirely and has to be turned away here - with 413, not with the 500 an
   * unhandled rejection would produce.
   */
  @Test
  void aLogoAboveTheSizeLimitIsRejectedWith413() throws Exception {
    byte[] tooLarge = new byte[BrandingLogoValidator.MAX_LOGO_SIZE_BYTES + 1];

    mockMvc
        .perform(
            multipart(HttpMethod.PUT, "/api/v1/system/branding/logo")
                .file(logoPart(tooLarge))
                .with(devUser("dev-admin")))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("KiB")));
  }

  @Test
  void theLogoEndpointAnswers404WhileNoLogoIsConfigured() throws Exception {
    mockMvc
        .perform(get("/api/v1/branding/logo").with(devUser("dev-user")))
        .andExpect(status().isNotFound());
  }

  @Test
  void anAdministratorRemovesTheLogoAgain() throws Exception {
    mockMvc.perform(
        multipart(HttpMethod.PUT, "/api/v1/system/branding/logo")
            .file(logoPart(png(120, 40)))
            .with(devUser("dev-admin")));

    mockMvc
        .perform(delete("/api/v1/system/branding/logo").with(devUser("dev-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.logoUrl").doesNotExist());

    mockMvc
        .perform(get("/api/v1/branding/logo").with(devUser("dev-user")))
        .andExpect(status().isNotFound());
  }

  private static MockMultipartFile logoPart(byte[] content) {
    return new MockMultipartFile("file", "logo.png", MediaType.IMAGE_PNG_VALUE, content);
  }

  private static byte[] png(int width, int height) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
    return out.toByteArray();
  }

  private RequestPostProcessor devUser(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      return request;
    };
  }
}
