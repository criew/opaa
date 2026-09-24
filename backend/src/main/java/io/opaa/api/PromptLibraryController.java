package io.opaa.api;

import io.opaa.api.dto.PromptLibraryRequest;
import io.opaa.api.dto.PromptLibraryResponse;
import io.opaa.api.dto.PromptLibraryUpdateRequest;
import io.opaa.api.dto.PromptRequest;
import io.opaa.api.dto.PromptResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.prompt.PromptLibraryService;
import io.opaa.prompt.PromptService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prompt libraries and their prompts. Grants, the Herleitung and the space association of a prompt
 * library are the asset shell's, under {@code /api/v1/assets/PROMPT_LIBRARY/{assetId}}.
 */
@RestController
@RequestMapping("/api/v1/prompt-libraries")
public class PromptLibraryController {

  private final PromptLibraryService libraryService;
  private final PromptService promptService;

  public PromptLibraryController(PromptLibraryService libraryService, PromptService promptService) {
    this.libraryService = libraryService;
    this.promptService = promptService;
  }

  @GetMapping
  public List<PromptLibraryResponse> listPromptLibraries(@Caller CurrentUser caller) {
    return PromptLibraryResponseMapper.toResponses(libraryService.list(caller));
  }

  @PostMapping
  public ResponseEntity<PromptLibraryResponse> createPromptLibrary(
      @Valid @RequestBody PromptLibraryRequest request, @Caller CurrentUser caller) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            PromptLibraryResponseMapper.toResponse(
                libraryService.create(PromptLibraryResponseMapper.toCreation(request), caller)));
  }

  @GetMapping("/{promptLibraryId}")
  public PromptLibraryResponse getPromptLibrary(
      @PathVariable UUID promptLibraryId, @Caller CurrentUser caller) {
    return PromptLibraryResponseMapper.toResponse(libraryService.get(promptLibraryId, caller));
  }

  @PutMapping("/{promptLibraryId}")
  public PromptLibraryResponse updatePromptLibrary(
      @PathVariable UUID promptLibraryId,
      @Valid @RequestBody PromptLibraryUpdateRequest request,
      @Caller CurrentUser caller) {
    return PromptLibraryResponseMapper.toResponse(
        libraryService.update(
            promptLibraryId, PromptLibraryResponseMapper.toUpdate(request), caller));
  }

  @DeleteMapping("/{promptLibraryId}")
  public ResponseEntity<Void> deletePromptLibrary(
      @PathVariable UUID promptLibraryId, @Caller CurrentUser caller) {
    libraryService.delete(promptLibraryId, caller);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{promptLibraryId}/prompts")
  public List<PromptResponse> listPrompts(
      @PathVariable UUID promptLibraryId, @Caller CurrentUser caller) {
    return PromptLibraryResponseMapper.toPromptResponses(
        promptService.list(promptLibraryId, caller));
  }

  @PostMapping("/{promptLibraryId}/prompts")
  public ResponseEntity<PromptResponse> createPrompt(
      @PathVariable UUID promptLibraryId,
      @Valid @RequestBody PromptRequest request,
      @Caller CurrentUser caller) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            PromptLibraryResponseMapper.toResponse(
                promptService.create(
                    promptLibraryId, PromptLibraryResponseMapper.toContent(request), caller)));
  }

  @GetMapping("/{promptLibraryId}/prompts/{promptId}")
  public PromptResponse getPrompt(
      @PathVariable UUID promptLibraryId, @PathVariable UUID promptId, @Caller CurrentUser caller) {
    return PromptLibraryResponseMapper.toResponse(
        promptService.get(promptLibraryId, promptId, caller));
  }

  @PutMapping("/{promptLibraryId}/prompts/{promptId}")
  public PromptResponse updatePrompt(
      @PathVariable UUID promptLibraryId,
      @PathVariable UUID promptId,
      @Valid @RequestBody PromptRequest request,
      @Caller CurrentUser caller) {
    return PromptLibraryResponseMapper.toResponse(
        promptService.update(
            promptLibraryId, promptId, PromptLibraryResponseMapper.toContent(request), caller));
  }

  @DeleteMapping("/{promptLibraryId}/prompts/{promptId}")
  public ResponseEntity<Void> deletePrompt(
      @PathVariable UUID promptLibraryId, @PathVariable UUID promptId, @Caller CurrentUser caller) {
    promptService.delete(promptLibraryId, promptId, caller);
    return ResponseEntity.noContent().build();
  }
}
