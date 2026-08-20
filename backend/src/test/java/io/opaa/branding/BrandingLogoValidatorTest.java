package io.opaa.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * #582's upload rules for the operator logo: format, size, pixel dimensions, and - the one that
 * carries the security argument - that the bytes themselves decide, not what an uploader claims.
 *
 * <p>Every image here is generated rather than checked in as a fixture: a test that proves "an SVG
 * is rejected" is only worth something if the SVG it feeds in is a real one, and a generated PNG
 * makes the "this really is a PNG the JRE can read" precondition explicit instead of implicit in a
 * binary blob nobody re-reads.
 */
class BrandingLogoValidatorTest {

  private final BrandingLogoValidator validator = new BrandingLogoValidator();

  @Test
  void acceptsAPngAndReportsTheTypeItDetectedInTheBytes() throws IOException {
    BrandingLogoValidator.ValidatedLogo logo = validator.validate(png(200, 80));

    assertThat(logo.contentType()).isEqualTo(BrandingLogoValidator.PNG_MIME_TYPE);
    assertThat(logo.version()).hasSize(16);
  }

  @Test
  void acceptsAJpeg() throws IOException {
    BrandingLogoValidator.ValidatedLogo logo = validator.validate(jpeg(200, 80));

    assertThat(logo.contentType()).isEqualTo(BrandingLogoValidator.JPEG_MIME_TYPE);
  }

  @Test
  void derivesTheVersionFromTheContent() throws IOException {
    byte[] content = png(200, 80);

    assertThat(validator.validate(content).version())
        .isEqualTo(validator.validate(content.clone()).version())
        .isNotEqualTo(validator.validate(png(201, 80)).version());
  }

  @Test
  void rejectsAnSvgEvenThoughItIsAnImageFormat() {
    byte[] svg =
        ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">"
                + "<script>alert(1)</script></svg>")
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> validator.validate(svg))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("SVG")
        .satisfies(e -> assertThat(status(e)).isEqualTo(HttpStatus.BAD_REQUEST.value()));
  }

  @Test
  void rejectsAFileThatIsNotAnImageAtAllNoMatterWhatItIsCalled() {
    byte[] html =
        "<html><body><script>alert(1)</script></body></html>".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> validator.validate(html))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(status(e)).isEqualTo(HttpStatus.BAD_REQUEST.value()));
  }

  @Test
  void rejectsContentAboveTheSizeLimitWith413() {
    byte[] tooLarge = new byte[BrandingLogoValidator.MAX_LOGO_SIZE_BYTES + 1];

    assertThatThrownBy(() -> validator.validate(tooLarge))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(status(e)).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value()));
  }

  @Test
  void rejectsEmptyContent() {
    assertThatThrownBy(() -> validator.validate(new byte[0]))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("leer");
  }

  @Test
  void rejectsAnImageWiderThanTheDimensionLimit() throws IOException {
    // A single-colour PNG compresses to a few kilobytes at this size - well inside the byte limit,
    // which is exactly the case the pixel limit exists for.
    byte[] oversized = png(BrandingLogoValidator.MAX_LOGO_EDGE_PIXELS + 1, 10);
    assertThat(oversized.length).isLessThan(BrandingLogoValidator.MAX_LOGO_SIZE_BYTES);

    assertThatThrownBy(() -> validator.validate(oversized))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Bildpunkte");
  }

  /**
   * Compares the numeric status, not the enum constant: {@code HttpStatus.valueOf(413)} resolves to
   * {@code CONTENT_TOO_LARGE}, while the production code (like the rest of this codebase) names the
   * same status {@code PAYLOAD_TOO_LARGE} - two constants, one status.
   */
  private static int status(Throwable e) {
    return ((ResponseStatusException) e).getStatusCode().value();
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
