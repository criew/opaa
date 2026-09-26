package io.opaa.branding;

/**
 * One configured branding image's bytes together with the media type they are served under (#582,
 * #1910). Loaded only by the endpoints of {@code BrandingController} that serve them - every other
 * read of the branding settings goes through {@link EffectiveBranding} and never pulls a {@code
 * bytea} column along.
 *
 * @param contentType the media type {@link BrandingImageValidator} detected in the bytes themselves
 *     at upload time. Serving this rather than an uploader-supplied header is what "Content-Type
 *     beim Ausliefern erzwingen" (#582) means in practice.
 * @param version short, content-derived version, also used as the response's {@code ETag}
 */
public record BrandingImage(byte[] content, String contentType, String version) {}
