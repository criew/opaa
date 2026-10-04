package io.opaa.library.web;

import io.opaa.api.dto.ConnectionProfileOption;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.connection.web.ConnectionProfileResponseMapper;
import io.opaa.knowledge.SourceType;
import io.opaa.library.KnowledgeLibraryService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The profiles a library's managers may connect it through ({@code listConnectionProfileOptions}
 * with {@code libraryId}); without one the connection administration answers.
 */
@RestController
public class LibraryProfileOptionController {

  private final KnowledgeLibraryService libraryService;

  public LibraryProfileOptionController(KnowledgeLibraryService libraryService) {
    this.libraryService = libraryService;
  }

  @GetMapping(value = "/api/v1/connection-profiles", params = "libraryId")
  public List<ConnectionProfileOption> listConnectionProfileOptionsForLibrary(
      @RequestParam String sourceType, @RequestParam UUID libraryId, @Caller CurrentUser caller) {
    if (!SourceType.isKey(sourceType)) {
      throw new ValidationException(
          "sourceType " + sourceType + " ist kein gültiger Quellentyp-Schlüssel");
    }
    return libraryService.profileOptions(libraryId, SourceType.of(sourceType), caller).stream()
        .map(ConnectionProfileResponseMapper::toOption)
        .toList();
  }
}
