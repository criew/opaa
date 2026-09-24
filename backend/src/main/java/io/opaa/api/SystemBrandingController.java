package io.opaa.api;

import io.opaa.api.dto.BrandingResponse;
import io.opaa.api.dto.BrandingUpdateRequest;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.branding.BrandingImageKind;
import io.opaa.branding.BrandingImageValidator;
import io.opaa.branding.BrandingSettingsService;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The write side of operator branding (#582), {@code SYSTEM_ADMIN} only. Separate from {@link
 * BrandingController} because the two have different audiences and different paths: reading is for
 * every signed-in user under {@code /api/v1/branding}, changing is an administrative act under
 * {@code /api/v1/system}.
 *
 * <p>Each image has its own endpoints rather than riding along in {@link #updateBranding}: welding
 * a binary upload into the JSON settings request would force every settings edit to be a multipart
 * request and would make "change the colour" and "replace the logo" indistinguishable to a caller
 * who only wants one of them. Every endpoint answers with the same {@link BrandingResponse} the
 * read endpoint returns, so a management form always has the effective state in hand after a write
 * without a follow-up request.
 */
@RestController
@RequestMapping("/api/v1/system/branding")
public class SystemBrandingController {

  private final BrandingSettingsService brandingSettingsService;
  private final BrandingImageValidator imageValidator;

  public SystemBrandingController(
      BrandingSettingsService brandingSettingsService, BrandingImageValidator imageValidator) {
    this.brandingSettingsService = brandingSettingsService;
    this.imageValidator = imageValidator;
  }

  /**
   * Replaces the non-binary branding fields. A full replacement: an omitted or {@code null} field
   * means "back to the OPAA default", not "leave as is" - see {@code
   * BrandingSettingsService#updateBranding}.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping
  public BrandingResponse updateBranding(
      @RequestBody BrandingUpdateRequest request, @Caller CurrentUser caller) {
    return BrandingResponseMapper.toResponse(
        brandingSettingsService.updateBranding(
            caller.organizationId(),
            caller.id(),
            request.getProductName(),
            request.getClaim(),
            request.getPrimaryColor(),
            request.getDefaultColorScheme()));
  }

  /**
   * Stores an uploaded logo. Every rule about what is acceptable - size, format, actual bytes,
   * pixel dimensions - lives in {@code BrandingImageValidator}, deliberately not here: this method
   * only turns the multipart part into the bytes that validator decides about.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping(path = "/logo", consumes = "multipart/form-data")
  public BrandingResponse updateBrandingLogo(
      @RequestPart("file") MultipartFile file, @Caller CurrentUser caller) {
    return store(BrandingImageKind.LOGO, file, caller);
  }

  /** Removes the configured logo; the app falls back to the bundled OPAA logo. Idempotent. */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/logo")
  public BrandingResponse deleteBrandingLogo(@Caller CurrentUser caller) {
    return remove(BrandingImageKind.LOGO, caller);
  }

  /** Stores the sign-in page's own logo (#1910), under the same rules as the app logo. */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping(path = "/login-logo", consumes = "multipart/form-data")
  public BrandingResponse updateBrandingLoginLogo(
      @RequestPart("file") MultipartFile file, @Caller CurrentUser caller) {
    return store(BrandingImageKind.LOGIN_LOGO, file, caller);
  }

  /** Removes the sign-in logo; the page falls back to the app logo. Idempotent. */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/login-logo")
  public BrandingResponse deleteBrandingLoginLogo(@Caller CurrentUser caller) {
    return remove(BrandingImageKind.LOGIN_LOGO, caller);
  }

  /** Stores the sign-in page's background image (#1910); its own, larger ceilings apply. */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping(path = "/login-background", consumes = "multipart/form-data")
  public BrandingResponse updateBrandingLoginBackground(
      @RequestPart("file") MultipartFile file, @Caller CurrentUser caller) {
    return store(BrandingImageKind.LOGIN_BACKGROUND, file, caller);
  }

  /** Removes the background image; the sign-in page returns to its plain panel. Idempotent. */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/login-background")
  public BrandingResponse deleteBrandingLoginBackground(@Caller CurrentUser caller) {
    return remove(BrandingImageKind.LOGIN_BACKGROUND, caller);
  }

  private BrandingResponse store(BrandingImageKind kind, MultipartFile file, CurrentUser caller) {
    return BrandingResponseMapper.toResponse(
        brandingSettingsService.replaceImage(
            caller.organizationId(), caller.id(), kind, bytesOf(kind, file)));
  }

  private BrandingResponse remove(BrandingImageKind kind, CurrentUser caller) {
    return BrandingResponseMapper.toResponse(
        brandingSettingsService.removeImage(caller.organizationId(), caller.id(), kind));
  }

  /**
   * Checks the declared size before reading anything: the container's own multipart limit is the 50
   * MiB document-upload one, so an oversized "logo" would otherwise land in the heap in full before
   * being rejected (see {@code BrandingImageValidator#requireAcceptableSize}).
   */
  private byte[] bytesOf(BrandingImageKind kind, MultipartFile file) {
    imageValidator.requireAcceptableSize(kind, file.getSize());
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Die hochgeladene Bilddatei konnte nicht gelesen werden", e);
    }
  }
}
