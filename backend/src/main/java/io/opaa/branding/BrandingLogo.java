package io.opaa.branding;

/**
 * A configured logo's bytes together with the media type they are served under (#582). Loaded only
 * by {@code io.opaa.api.BrandingController#getBrandingLogo} - every other read of the branding
 * settings goes through {@link EffectiveBranding} and never pulls the {@code bytea} column along.
 *
 * @param contentType the media type {@link BrandingLogoValidator} detected in the bytes themselves
 *     at upload time. Serving this rather than an uploader-supplied header is what "Content-Type
 *     beim Ausliefern erzwingen" (#582) means in practice.
 * @param version short, content-derived version, also used as the response's {@code ETag}
 */
public record BrandingLogo(byte[] content, String contentType, String version) {}
