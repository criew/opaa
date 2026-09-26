/**
 * Operator branding as configuration rather than code (#582, docs/design/guidelines.md#7): product
 * name, claim, images, accent colour and the deployment's default colour scheme, changeable through
 * the web interface and effective without a rebuild or a redeployment.
 *
 * <p>{@link io.opaa.branding.BrandingSettingsService} is the single entry point - it resolves the
 * stored {@link io.opaa.branding.BrandingSettings} singleton row against the {@link
 * io.opaa.branding.BrandingDefaults OPAA standard} field by field, so a deployment that configured
 * nothing is indistinguishable from the standard, and validates every change before it reaches the
 * database. {@link io.opaa.branding.BrandingImageValidator} carries the upload rules that keep an
 * uploaded image from becoming an execution vector - see its Javadoc for why SVG is rejected
 * outright rather than sanitised.
 *
 * <p>{@code BrandingController} exposes the read path to every signed-in user; {@code
 * SystemBrandingController} exposes the write path to {@code SystemRole.SYSTEM_ADMIN} alone.
 */
package io.opaa.branding;
