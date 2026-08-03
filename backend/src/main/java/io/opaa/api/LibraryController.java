package io.opaa.api;

import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.api.dto.LibraryListResponse;
import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.library.KnowledgeLibraryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Profile({"oidc", "basic"})
@RestController
@RequestMapping("/api/v1/libraries")
public class LibraryController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final KnowledgeLibraryService libraryService;
  private final UserService userService;

  public LibraryController(KnowledgeLibraryService libraryService, UserService userService) {
    this.libraryService = libraryService;
    this.userService = userService;
  }

  @PostMapping
  public ResponseEntity<LibraryResponse> createLibrary(
      @Valid @RequestBody LibraryRequest request, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    LibraryResponse response = libraryService.createLibrary(request, currentUser.getId());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping
  public List<LibraryListResponse> listLibraries(@AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return libraryService.listLibraries(currentUser.getId());
  }

  @GetMapping("/{libraryId}")
  public LibraryResponse getLibrary(
      @PathVariable UUID libraryId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return libraryService.getLibrary(
        libraryId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @PutMapping("/{libraryId}")
  public LibraryResponse updateLibrary(
      @PathVariable UUID libraryId,
      @Valid @RequestBody LibraryUpdateRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return libraryService.updateLibrary(
        libraryId,
        request,
        currentUser.getId(),
        currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @DeleteMapping("/{libraryId}")
  public ResponseEntity<Void> deleteLibrary(
      @PathVariable UUID libraryId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    libraryService.deleteLibrary(
        libraryId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{libraryId}/documents")
  public List<LibraryDocumentResponse> listDocuments(
      @PathVariable UUID libraryId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return libraryService.listDocuments(
        libraryId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
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
