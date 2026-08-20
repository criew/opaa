package io.opaa.api;

import io.opaa.api.dto.BrandingResponse;
import io.opaa.branding.BrandingLogo;
import io.opaa.branding.BrandingSettingsService;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The read side of operator branding (#582) - open to every signed-in user, not just
 * administrators, because every rendered page needs it. The write side lives in {@link
 * SystemBrandingController} under {@code /api/v1/system}, restricted to {@code
 * SystemRole.SYSTEM_ADMIN}.
 *
 * <p><b>Not reachable without authentication</b>, matching #582's own wording ("lesbar für alle
 * angemeldeten Nutzer"): both security chains ({@code DevSecurityConfig}/{@code
 * OidcSecurityConfig}) authenticate everything under {@code /api/**} except the handful of paths
 * they list explicitly, and this is not one of them. The consequence is worth naming: the sign-in
 * page (#588) renders before there is a session, so it cannot brand itself from this endpoint - see
 * the pull request for why widening that is a deliberate decision for the maintainer rather than
 * one folded in here.
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
   * time (#582: "Content-Type beim Ausliefern erzwingen"). Three headers do the actual work of
   * keeping a stored file from becoming an execution vector, and each is load-bearing:
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
   * <p>{@link io.opaa.branding.BrandingLogoValidator} is what makes those headers a second line of
   * defense rather than the only one - the bytes were already required to be a real PNG or JPEG.
   */
  @GetMapping("/logo")
  public ResponseEntity<byte[]> getBrandingLogo() {
    BrandingLogo logo =
        brandingSettingsService
            .currentLogo()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Es ist kein Logo konfiguriert"));

    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(logo.contentType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"logo\"")
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "default-src 'none'; sandbox")
        // The URL carries the content-derived version (see BrandingResponseMapper#logoUrl), so a
        // changed logo is a different URL and this cache entry can never go stale. The ETag covers
        // the case of a client that ignores the version parameter and re-requests the bare path.
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
        .eTag(logo.version())
        .body(logo.content());
  }
}
