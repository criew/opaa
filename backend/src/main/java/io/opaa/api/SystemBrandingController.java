package io.opaa.api;

import io.opaa.api.dto.BrandingResponse;
import io.opaa.api.dto.BrandingUpdateRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.branding.BrandingLogoValidator;
import io.opaa.branding.BrandingSettingsService;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * The write side of operator branding (#582), {@code SYSTEM_ADMIN} only. Separate from {@link
 * BrandingController} because the two have different audiences and different paths: reading is for
 * every signed-in user under {@code /api/v1/branding}, changing is an administrative act under
 * {@code /api/v1/system}.
 *
 * <p>The logo has its own endpoints rather than riding along in {@link #updateBranding}: welding a
 * binary upload into the JSON settings request would force every settings edit to be a multipart
 * request and would make "change the colour" and "replace the logo" indistinguishable to a caller
 * who only wants one of them. All three endpoints answer with the same {@link BrandingResponse} the
 * read endpoint returns, so a management form always has the effective state in hand after a write
 * without a follow-up request.
 */
@RestController
@RequestMapping("/api/v1/system/branding")
public class SystemBrandingController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final BrandingSettingsService brandingSettingsService;
  private final BrandingLogoValidator logoValidator;
  private final UserService userService;

  public SystemBrandingController(
      BrandingSettingsService brandingSettingsService,
      BrandingLogoValidator logoValidator,
      UserService userService) {
    this.brandingSettingsService = brandingSettingsService;
    this.logoValidator = logoValidator;
    this.userService = userService;
  }

  /**
   * Replaces the non-binary branding fields. A full replacement: an omitted or {@code null} field
   * means "back to the OPAA default", not "leave as is" - see {@code
   * BrandingSettingsService#updateBranding}.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping
  public BrandingResponse updateBranding(
      @RequestBody BrandingUpdateRequest request, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return BrandingResponseMapper.toResponse(
        brandingSettingsService.updateBranding(
            currentUser.getOrganizationId(),
            currentUser.getId(),
            request.getProductName(),
            request.getClaim(),
            request.getPrimaryColor(),
            request.getDefaultColorScheme()));
  }

  /**
   * Stores an uploaded logo. Every rule about what is acceptable - size, format, actual bytes,
   * pixel dimensions - lives in {@code BrandingLogoValidator}, deliberately not here: this method
   * only turns the multipart part into the bytes that validator decides about.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping(path = "/logo", consumes = "multipart/form-data")
  public BrandingResponse updateBrandingLogo(
      @RequestPart("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return BrandingResponseMapper.toResponse(
        brandingSettingsService.replaceLogo(
            currentUser.getOrganizationId(), currentUser.getId(), bytesOf(file)));
  }

  /** Removes the configured logo; the app falls back to the bundled OPAA logo. Idempotent. */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/logo")
  public BrandingResponse deleteBrandingLogo(@AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return BrandingResponseMapper.toResponse(
        brandingSettingsService.removeLogo(currentUser.getOrganizationId(), currentUser.getId()));
  }

  /**
   * Checks the declared size before reading anything: the container's own multipart limit is the 50
   * MiB document-upload one, so an oversized "logo" would otherwise land in the heap in full before
   * being rejected (see {@code BrandingLogoValidator#requireAcceptableSize}).
   */
  private byte[] bytesOf(MultipartFile file) {
    logoValidator.requireAcceptableSize(file.getSize());
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Die hochgeladene Logo-Datei konnte nicht gelesen werden", e);
    }
  }

  private User currentUser(Jwt jwt) {
    String issuer = jwt.getClaimAsString("iss");
    if (issuer == null || issuer.isBlank()) {
      issuer = UNKNOWN_ISSUER;
    }

    return userService
        .findBySubjectAndIssuer(jwt.getSubject(), issuer)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Benutzer nicht gefunden"));
  }
}
