package io.opaa.branding;

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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Everything that has to be true of an uploaded logo before it is stored (#582: "Validierung an der
 * Systemgrenze ... Logo-MIME-Typ und -Größe" and "das hochgeladene Logo darf kein Skript ausführen
 * können").
 *
 * <p><b>SVG is rejected, not sanitised.</b> #582 allows either; rejecting is the choice that cannot
 * be wrong on a bad day. An SVG is a document, not an image file - it can carry {@code <script>},
 * event handlers, external references and {@code <foreignObject>} - and a sanitiser that misses one
 * construct hands script execution to every user's browser under the deployment's own origin. A
 * format restriction is inconvenient once, when the operator converts their logo; a sanitiser bug
 * is a cross-site scripting hole in a page every user of the deployment loads.
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
public class BrandingLogoValidator {

  /**
   * 512 KiB. A logo is a piece of chrome rendered a few hundred pixels wide; anything past this is
   * either a photograph pasted into the wrong form or an attempt to see what happens.
   */
  public static final int MAX_LOGO_SIZE_BYTES = 512 * 1024;

  /**
   * 2000 px per edge. Not about layout - about not accepting a small file that decompresses into a
   * very large raster ("decompression bomb"): the dimensions are read from the image header, before
   * anything decodes a single pixel.
   */
  public static final int MAX_LOGO_EDGE_PIXELS = 2000;

  public static final String PNG_MIME_TYPE = "image/png";
  public static final String JPEG_MIME_TYPE = "image/jpeg";

  private static final Set<String> ACCEPTED_MIME_TYPES = Set.of(PNG_MIME_TYPE, JPEG_MIME_TYPE);

  // A single shared instance - Tika's facade is thread-safe and building one per upload would only
  // repeat its detector initialisation (same reasoning as LibraryDocumentService's own field).
  private final Tika tika = new Tika();

  /**
   * Validates {@code content} and returns what should be stored for it. Throws a {@link
   * ResponseStatusException} with a German-language message for every rejection - 413 for "too
   * large", 400 for everything else - so a caller does not have to translate anything.
   */
  public ValidatedLogo validate(byte[] content) {
    if (content == null || content.length == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Die Logo-Datei ist leer");
    }
    requireAcceptableSize(content.length);

    String detectedMimeType = detectMimeType(content);
    if (!ACCEPTED_MIME_TYPES.contains(detectedMimeType)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Als Logo sind nur PNG- und JPEG-Dateien zulässig; SVG wird bewusst nicht angenommen,"
              + " weil eine SVG-Datei Skripte enthalten kann");
    }

    requireSaneDimensions(content, detectedMimeType);

    return new ValidatedLogo(content, detectedMimeType, version(content));
  }

  /**
   * The size rule on its own, so a caller can apply it to a declared size before reading anything
   * into memory. {@code spring.servlet.multipart.max-file-size} is bound to the document-upload
   * limit of 50 MiB (application.yml), which is two orders of magnitude past what a logo may be -
   * without this, {@code MultipartFile#getBytes} would pull a 50 MiB "logo" fully into the heap
   * only for {@link #validate} to reject it a line later. {@link #validate} still applies the same
   * rule to the bytes it actually got, so this stays an optimisation rather than the guarantee.
   */
  public void requireAcceptableSize(long sizeBytes) {
    if (sizeBytes > MAX_LOGO_SIZE_BYTES) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "Das Logo darf höchstens " + (MAX_LOGO_SIZE_BYTES / 1024) + " KiB groß sein");
    }
  }

  private String detectMimeType(byte[] content) {
    try (InputStream contentStream = new ByteArrayInputStream(content)) {
      return tika.detect(contentStream);
    } catch (IOException e) {
      // Unreachable for a ByteArrayInputStream, but IOException is checked and swallowing it
      // silently would turn a genuine detection failure into "accepted".
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Das Logo konnte nicht auf sein Format geprüft werden");
    }
  }

  /**
   * Reads width and height from the image header without decoding the image - {@link
   * ImageReader#getWidth}/{@link ImageReader#getHeight} parse the header alone, unlike {@code
   * ImageIO.read}, which would allocate the full raster of exactly the oversized image this check
   * exists to reject.
   */
  private void requireSaneDimensions(byte[] content, String mimeType) {
    try (ImageInputStream imageStream =
        ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
      Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(mimeType);
      if (!readers.hasNext()) {
        // Only reachable if the accepted-type set and the JRE's readers ever drift apart; failing
        // closed here is what keeps that drift from silently disabling this check.
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Das Logo konnte nicht als Bild gelesen werden");
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(imageStream);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width > MAX_LOGO_EDGE_PIXELS || height > MAX_LOGO_EDGE_PIXELS) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "Das Logo darf höchstens "
                  + MAX_LOGO_EDGE_PIXELS
                  + " × "
                  + MAX_LOGO_EDGE_PIXELS
                  + " Bildpunkte groß sein, war aber "
                  + width
                  + " × "
                  + height);
        }
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Das Logo konnte nicht als Bild gelesen werden");
    }
  }

  /**
   * A short, content-derived version - the first 16 hex characters of the content's SHA-256. Used
   * both as the cache-busting query parameter in {@code logoUrl} and as the {@code ETag} the
   * logo-serving endpoint returns, so both change exactly when the bytes do. Truncated because this
   * identifies a version, it does not authenticate one.
   */
  private String version(byte[] content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
      return HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required to be available on every JRE", e);
    }
  }

  /** An accepted logo: the bytes, the type detected in them, and their content-derived version. */
  public record ValidatedLogo(byte[] content, String contentType, String version) {}
}
