package io.opaa.branding.web;

import io.opaa.api.dto.BrandingResponse;
import io.opaa.branding.BrandingImage;
import io.opaa.branding.BrandingImageKind;
import io.opaa.branding.BrandingSettingsService;
import io.opaa.common.NotFoundException;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read side of operator branding - open to every signed-in user, not just administrators,
 * because every rendered page needs it. The write side lives in {@link SystemBrandingController}
 * under {@code /api/v1/system}, restricted to {@code SystemRole.SYSTEM_ADMIN}.
 *
 * <p><b>Reachable without authentication at all</b> - both security chains ({@code
 * DevSecurityConfig}/{@code OidcSecurityConfig}) list these paths among their {@code permitAll}
 * exceptions: the sign-in page renders before there is a session and still carries the operator's
 * product name, claim and logo. What this exposes is deliberate and bounded - the name, claim,
 * accent colour and images of the deployment, i.e. which Behörde runs it, which anyone reaching its
 * sign-in page can already tell. No user, space, library or configuration data is reachable through
 * either path, and every write still requires {@code SYSTEM_ADMIN} ({@link
 * SystemBrandingController}). {@code BrandingPublicAccessTest} proves both halves.
 */
@RestController
@RequestMapping("/api/v1/branding")
public class BrandingController {

  private final BrandingSettingsService brandingSettingsService;

  public BrandingController(BrandingSettingsService brandingSettingsService) {
    this.brandingSettingsService = brandingSettingsService;
  }

  @GetMapping
  public BrandingResponse getBranding() {
    return BrandingResponseMapper.toResponse(brandingSettingsService.currentBranding());
  }

  /**
   * Serves the configured logo's bytes under the content type the server itself detected at upload
   * time ("Content-Type beim Ausliefern erzwingen"). Three headers do the actual work of keeping a
   * stored file from becoming an execution vector, and each is load-bearing:
   *
   * <ul>
   *   <li>{@code X-Content-Type-Options: nosniff} - without it a browser may disregard the declared
   *       type and sniff the bytes, which is the whole mechanism by which a file uploaded as an
   *       image gets treated as something else.
   *   <li>{@code Content-Disposition: inline} with an explicit, fixed file name - the response
   *       never carries an uploader-influenced name that a download could land under.
   *   <li>{@code Content-Security-Policy: default-src 'none'; sandbox} - belt and braces for the
   *       case where the response is opened as a top-level document rather than as an {@code <img>}
   *       source: nothing it might contain may load or execute anything.
   * </ul>
   *
   * <p>{@link io.opaa.branding.BrandingImageValidator} is what makes those headers a second line of
   * defense rather than the only one - the bytes were already required to be a real PNG or JPEG.
   */
  @GetMapping("/logo")
  public ResponseEntity<byte[]> getBrandingLogo() {
    return serve(BrandingImageKind.LOGO, "logo");
  }

  /** The sign-in page's own logo, served exactly like {@link #getBrandingLogo}. */
  @GetMapping("/login-logo")
  public ResponseEntity<byte[]> getBrandingLoginLogo() {
    return serve(BrandingImageKind.LOGIN_LOGO, "login-logo");
  }

  /** The sign-in page's background image, served exactly like {@link #getBrandingLogo}. */
  @GetMapping("/login-background")
  public ResponseEntity<byte[]> getBrandingLoginBackground() {
    return serve(BrandingImageKind.LOGIN_BACKGROUND, "login-background");
  }

  private ResponseEntity<byte[]> serve(BrandingImageKind kind, String fileName) {
    BrandingImage image =
        brandingSettingsService
            .currentImage(kind)
            .orElseThrow(() -> new NotFoundException(kind.label() + " ist nicht konfiguriert"));

    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(image.contentType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "default-src 'none'; sandbox")
        // The URL carries the content-derived version (see BrandingResponseMapper), so a changed
        // image is a different URL and this cache entry can never go stale. The ETag covers the
        // case of a client that ignores the version parameter and re-requests the bare path.
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
        .eTag(image.version())
        .body(image.content());
  }
}
