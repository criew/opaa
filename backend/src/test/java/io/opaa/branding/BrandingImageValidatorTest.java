package io.opaa.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ValidationException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * #582's upload rules for the operator's branding images: format, size, pixel dimensions, and - the
 * one that carries the security argument - that the bytes themselves decide, not what an uploader
 * claims. Since #1910 the same validator serves three kinds, which is why the ceilings are read
 * from {@link BrandingImageKind} here rather than hard-coded: a kind whose limits drift away from
 * the ones the database's own constraints carry would otherwise pass unnoticed.
 *
 * <p>Every image here is generated rather than checked in as a fixture: a test that proves "an SVG
 * is rejected" is only worth something if the SVG it feeds in is a real one, and a generated PNG
 * makes the "this really is a PNG the JRE can read" precondition explicit instead of implicit in a
 * binary blob nobody re-reads.
 */
class BrandingImageValidatorTest {

  private final BrandingImageValidator validator = new BrandingImageValidator();

  @Test
  void acceptsAPngAndReportsTheTypeItDetectedInTheBytes() throws IOException {
    BrandingImageValidator.ValidatedImage logo =
        validator.validate(BrandingImageKind.LOGO, png(200, 80));

    assertThat(logo.contentType()).isEqualTo(BrandingImageValidator.PNG_MIME_TYPE);
    assertThat(logo.version()).hasSize(16);
  }

  @Test
  void acceptsAJpeg() throws IOException {
    BrandingImageValidator.ValidatedImage logo =
        validator.validate(BrandingImageKind.LOGO, jpeg(200, 80));

    assertThat(logo.contentType()).isEqualTo(BrandingImageValidator.JPEG_MIME_TYPE);
  }

  @Test
  void derivesTheVersionFromTheContent() throws IOException {
    byte[] content = png(200, 80);

    assertThat(validator.validate(BrandingImageKind.LOGO, content).version())
        .isEqualTo(validator.validate(BrandingImageKind.LOGO, content.clone()).version())
        .isNotEqualTo(validator.validate(BrandingImageKind.LOGO, png(201, 80)).version());
  }

  @Test
  void rejectsAnSvgEvenThoughItIsAnImageFormat() {
    byte[] svg =
        ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">"
                + "<script>alert(1)</script></svg>")
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> validator.validate(BrandingImageKind.LOGO, svg))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("SVG");
  }

  @Test
  void rejectsAFileThatIsNotAnImageAtAllNoMatterWhatItIsCalled() {
    byte[] html =
        "<html><body><script>alert(1)</script></body></html>".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> validator.validate(BrandingImageKind.LOGO, html))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void rejectsContentAboveTheSizeLimitWith413() {
    byte[] tooLarge = new byte[BrandingImageKind.LOGO.maxSizeBytes() + 1];

    assertThatThrownBy(() -> validator.validate(BrandingImageKind.LOGO, tooLarge))
        .isInstanceOf(PayloadTooLargeException.class);
  }

  @Test
  void rejectsEmptyContent() {
    assertThatThrownBy(() -> validator.validate(BrandingImageKind.LOGO, new byte[0]))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("leer");
  }

  @Test
  void rejectsAnImageWiderThanTheDimensionLimit() throws IOException {
    // A single-colour PNG compresses to a few kilobytes at this size - well inside the byte limit,
    // which is exactly the case the pixel limit exists for.
    byte[] oversized = png(BrandingImageKind.LOGO.maxEdgePixels() + 1, 10);
    assertThat(oversized.length).isLessThan(BrandingImageKind.LOGO.maxSizeBytes());

    assertThatThrownBy(() -> validator.validate(BrandingImageKind.LOGO, oversized))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Bildpunkte");
  }

  /**
   * #1910: the background image is a photograph covering half a screen, so an edge that the logo
   * rules reject has to pass here - otherwise the new kind silently inherits a ceiling meant for
   * chrome.
   */
  @Test
  void acceptsABackgroundLargerThanALogoMayBe() throws IOException {
    byte[] wide = png(BrandingImageKind.LOGO.maxEdgePixels() + 200, 800);

    assertThatThrownBy(() -> validator.validate(BrandingImageKind.LOGO, wide))
        .isInstanceOf(ValidationException.class);
    assertThat(validator.validate(BrandingImageKind.LOGIN_BACKGROUND, wide).contentType())
        .isEqualTo(BrandingImageValidator.PNG_MIME_TYPE);
  }

  /** The background's ceiling is larger, not absent - and its rejection is still a 413. */
  @Test
  void rejectsABackgroundAboveItsOwnLargerCeilings() throws IOException {
    assertThatThrownBy(
            () ->
                validator.validate(
                    BrandingImageKind.LOGIN_BACKGROUND,
                    new byte[BrandingImageKind.LOGIN_BACKGROUND.maxSizeBytes() + 1]))
        .isInstanceOf(PayloadTooLargeException.class);

    byte[] tooWide = png(BrandingImageKind.LOGIN_BACKGROUND.maxEdgePixels() + 1, 10);
    assertThatThrownBy(() -> validator.validate(BrandingImageKind.LOGIN_BACKGROUND, tooWide))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Bildpunkte");
  }

  /** Every kind rejects SVG - the security argument is about the format, not about the slot. */
  @Test
  void rejectsAnSvgForEveryKind() {
    byte[] svg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"></svg>"
            .getBytes(StandardCharsets.UTF_8);

    for (BrandingImageKind kind : BrandingImageKind.values()) {
      assertThatThrownBy(() -> validator.validate(kind, svg))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("SVG");
    }
  }

  private static byte[] png(int width, int height) throws IOException {
    return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png");
  }

  private static byte[] jpeg(int width, int height) throws IOException {
    return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
  }

  private static byte[] encode(BufferedImage image, String format) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, format, out);
    return out.toByteArray();
  }
}
