package io.opaa.api;

import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.library.DocumentContent;
import io.opaa.library.LibraryDocumentService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves the original file behind an indexed document (#736), so a search result or a library
 * listing can link straight to the source it was built from - the read counterpart to {@code
 * LibraryController}'s upload/delete document endpoints, split into its own controller because the
 * library owning the document is resolved from {@code Document.libraryId} rather than named in the
 * path (see {@link LibraryDocumentService#loadContent}'s Javadoc for the full access, sourceType
 * and traversal checks this delegates to).
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final LibraryDocumentService documentService;
  private final UserService userService;

  public DocumentController(LibraryDocumentService documentService, UserService userService) {
    this.documentService = documentService;
    this.userService = userService;
  }

  /**
   * {@code Content-Disposition: inline} with the document's own file name, URL-encoded (RFC 5987
   * {@code filename*}) rather than embedded raw - the same reasoning {@link
   * BrandingController#getBrandingLogo} documents for its own, fixed file name applies here to a
   * caller-influenced one: an unescaped file name containing a quote or CR/LF could otherwise break
   * out of the header value. Unlike the logo endpoint, the content type varies per document, so it
   * is taken from {@link DocumentContent#contentType()} rather than fixed to image formats.
   */
  @GetMapping("/{documentId}/content")
  public ResponseEntity<Resource> getDocumentContent(
      @PathVariable UUID documentId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    DocumentContent content =
        documentService.loadContent(
            documentId,
            currentUser.getId(),
            currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);

    long fileSize;
    try {
      fileSize = Files.size(content.path());
    } catch (IOException e) {
      throw new UncheckedIOException("Datei konnte nicht gelesen werden", e);
    }

    String encodedFileName =
        URLEncoder.encode(content.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(content.contentType()))
        .contentLength(fileSize)
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedFileName)
        .header("X-Content-Type-Options", "nosniff")
        .body(new FileSystemResource(content.path()));
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
