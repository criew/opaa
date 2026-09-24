package io.opaa.branding;

import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ValidationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

/**
 * Everything that has to be true of an uploaded branding image before it is stored (#582:
 * "Validierung an der Systemgrenze ... Logo-MIME-Typ und -Größe" and "das hochgeladene Logo darf
 * kein Skript ausführen können"). Since #1910 the same rules cover the sign-in page's own logo and
 * its background image; only the ceilings differ, and they come from {@link BrandingImageKind}.
 *
 * <p><b>SVG is rejected, not sanitised.</b> #582 allows either; rejecting is the choice that cannot
 * be wrong on a bad day. An SVG is a document, not an image file - it can carry {@code <script>},
 * event handlers, external references and {@code <foreignObject>} - and a sanitiser that misses one
 * construct hands script execution to every user's browser under the deployment's own origin. A
 * format restriction is inconvenient once, when the operator converts their logo; a sanitiser bug
 * is a cross-site scripting hole in a page every user of the deployment loads. This is also why
 * #1910's wish for SVG on the sign-in page is not granted: that page is the one surface an
 * unauthenticated visitor reaches.
 *
 * <p><b>PNG and JPEG, and nothing else</b> - narrower than the "images generally" one might expect,
 * and deliberately so: every accepted format is validated the same, complete way, and both of these
 * have an {@link ImageIO} reader in the JRE, so {@link #requireSaneDimensions} can actually run.
 * WebP has no reader on this classpath, so accepting it would mean accepting a format whose pixel
 * dimensions this class cannot check - a silently weaker rule for one format, which is worse than
 * not offering it.
 *
 * <p><b>The declared content type is never trusted.</b> The bytes are what decides: Tika's
 * magic-byte detection runs over the uploaded content, and the detected type - not the {@code
 * Content-Type} header the uploader chose - is what gets stored and later served (the same rule
 * {@code LibraryDocumentService#requireContentMatchesExtension} applies to document uploads, #435).
 */
@Component
public class BrandingImageValidator {

  public static final String PNG_MIME_TYPE = "image/png";
  public static final String JPEG_MIME_TYPE = "image/jpeg";

  private static final Set<String> ACCEPTED_MIME_TYPES = Set.of(PNG_MIME_TYPE, JPEG_MIME_TYPE);

  // A single shared instance - Tika's facade is thread-safe and building one per upload would only
  // repeat its detector initialisation (same reasoning as LibraryDocumentService's own field).
  private final Tika tika = new Tika();

  /**
   * Validates {@code content} against the rules of {@code kind} and returns what should be stored
   * for it. Throws {@link PayloadTooLargeException} (413, "too large") or {@link
   * ValidationException} (400, everything else) with a German-language message for every rejection,
   * so a caller does not have to translate anything.
   */
  public ValidatedImage validate(BrandingImageKind kind, byte[] content) {
    if (content == null || content.length == 0) {
      throw new ValidationException(kind.label() + " ist leer");
    }
    requireAcceptableSize(kind, content.length);

    String detectedMimeType = detectMimeType(kind, content);
    if (!ACCEPTED_MIME_TYPES.contains(detectedMimeType)) {
      throw new ValidationException(
          kind.label()
              + " muss eine PNG- oder JPEG-Datei sein; SVG wird bewusst nicht angenommen, weil eine"
              + " SVG-Datei Skripte enthalten kann");
    }

    requireSaneDimensions(kind, content, detectedMimeType);

    return new ValidatedImage(content, detectedMimeType, version(content));
  }

  /**
   * The size rule on its own, so a caller can apply it to a declared size before reading anything
   * into memory. {@code spring.servlet.multipart.max-file-size} is bound to the document-upload
   * limit of 50 MiB (application.yml), which is orders of magnitude past what a branding image may
   * be - without this, {@code MultipartFile#getBytes} would pull a 50 MiB "logo" fully into the
   * heap only for {@link #validate} to reject it a line later. {@link #validate} still applies the
   * same rule to the bytes it actually got, so this stays an optimisation rather than the
   * guarantee.
   */
  public void requireAcceptableSize(BrandingImageKind kind, long sizeBytes) {
    if (sizeBytes > kind.maxSizeBytes()) {
      throw new PayloadTooLargeException(
          kind.label() + " darf höchstens " + (kind.maxSizeBytes() / 1024) + " KiB groß sein");
    }
  }

  private String detectMimeType(BrandingImageKind kind, byte[] content) {
    try (InputStream contentStream = new ByteArrayInputStream(content)) {
      return tika.detect(contentStream);
    } catch (IOException e) {
      // Unreachable for a ByteArrayInputStream, but IOException is checked and swallowing it
      // silently would turn a genuine detection failure into "accepted".
      throw new ValidationException(kind.label() + " konnte nicht auf sein Format geprüft werden");
    }
  }

  /**
   * Reads width and height from the image header without decoding the image - {@link
   * ImageReader#getWidth}/{@link ImageReader#getHeight} parse the header alone, unlike {@code
   * ImageIO.read}, which would allocate the full raster of exactly the oversized image this check
   * exists to reject.
   */
  private void requireSaneDimensions(BrandingImageKind kind, byte[] content, String mimeType) {
    try (ImageInputStream imageStream =
        ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
      Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(mimeType);
      if (!readers.hasNext()) {
        // Only reachable if the accepted-type set and the JRE's readers ever drift apart; failing
        // closed here is what keeps that drift from silently disabling this check.
        throw new ValidationException(kind.label() + " konnte nicht als Bild gelesen werden");
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(imageStream);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width > kind.maxEdgePixels() || height > kind.maxEdgePixels()) {
          throw new ValidationException(
              kind.label()
                  + " darf höchstens "
                  + kind.maxEdgePixels()
                  + " × "
                  + kind.maxEdgePixels()
                  + " Bildpunkte groß sein, war aber "
                  + width
                  + " × "
                  + height);
        }
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new ValidationException(kind.label() + " konnte nicht als Bild gelesen werden");
    }
  }

  /**
   * A short, content-derived version - the first 16 hex characters of the content's SHA-256. Used
   * both as the cache-busting query parameter in the image URLs and as the {@code ETag} the
   * image-serving endpoints return, so both change exactly when the bytes do. Truncated because
   * this identifies a version, it does not authenticate one.
   */
  private String version(byte[] content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
      return HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required to be available on every JRE", e);
    }
  }

  /** An accepted image: the bytes, the type detected in them, and their content-derived version. */
  public record ValidatedImage(byte[] content, String contentType, String version) {}
}
