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
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #582's acceptance criteria at the HTTP boundary, against the real {@code dev} security chain
 * ({@link DevAuthFilter}, {@code UserProvisioningFilter}, the real {@code @PreAuthorize} on {@code
 * SystemBrandingController}) and a real Postgres - the same shape {@link
 * AuditControllerAuthorizationIntegrationTest} and {@link
 * LibraryIndexingAuthorizationIntegrationTest} already use, and for the same reason: a
 * {@code @WebMvcTest} slice would have to stub the very authorization decision the criterion is
 * about ("PUT verweigert Nicht-Administratoren (403)").
 *
 * <p>Deliberately one class for every HTTP-level concern of this feature rather than one per
 * endpoint: each {@code @SpringBootTest} declaring its own {@code @DynamicPropertySource} gets its
 * own ApplicationContext and its own Postgres container, and that accumulation is what {@code
 * build.gradle.kts}'s heap-ceiling comment describes. Everything that does not need MockMvc lives
 * in {@code BrandingSettingsServiceIntegrationTest}, which shares an already-cached context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class BrandingControllerIntegrationTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

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
   * #582: "lesbar für alle angemeldeten Nutzer" - the read endpoint is deliberately not
   * administrator-only, because every rendered page needs it. The other half of that sentence (that
   * it is not reachable without authentication) cannot be asserted from this profile: {@link
   * DevAuthFilter} authenticates every request as the configured default user, so a header-less
   * request here is not an anonymous one. What guarantees it is that neither security chain lists
   * this path among its {@code permitAll} exceptions, and everything else under {@code /api/**} is
   * {@code authenticated()} - see {@code BrandingController}'s own Javadoc.
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
