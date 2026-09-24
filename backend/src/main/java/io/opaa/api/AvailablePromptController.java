package io.opaa.api;

import io.opaa.api.dto.AvailablePrompt;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.prompt.PromptLibrary;
import io.opaa.prompt.PromptService;
import io.opaa.space.SpaceAssetAssociationService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The prompts a person can insert in the chat (#1903). Readability is the formula's, through {@link
 * PromptService#available}; the space only orders, through its associations.
 */
@RestController
public class AvailablePromptController {

  private final PromptService promptService;
  private final SpaceAssetAssociationService associationService;

  public AvailablePromptController(
      PromptService promptService, SpaceAssetAssociationService associationService) {
    this.promptService = promptService;
    this.associationService = associationService;
  }

  @GetMapping("/api/v1/prompts/available")
  public List<AvailablePrompt> listAvailablePrompts(
      @RequestParam(required = false) UUID spaceId, @Caller CurrentUser caller) {
    Set<UUID> spaceLibraryIds =
        spaceId == null
            ? Set.of()
            : associationService.assetIdsInSpace(spaceId, PromptLibrary.ASSET_TYPE, caller);
    return AvailablePromptResponseMapper.toResponses(
        promptService.available(caller, spaceLibraryIds));
  }
}
