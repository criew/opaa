package io.opaa.prompt.web;

import io.opaa.api.dto.AvailablePrompt;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.prompt.PromptService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The prompts a person can insert in a chat of the space: associated with it and readable by the
 * formula, through {@link PromptService#available}.
 */
@RestController
public class AvailablePromptController {

  private final PromptService promptService;

  public AvailablePromptController(PromptService promptService) {
    this.promptService = promptService;
  }

  @GetMapping("/api/v1/prompts/available")
  public List<AvailablePrompt> listAvailablePrompts(
      @RequestParam UUID spaceId, @Caller CurrentUser caller) {
    return AvailablePromptResponseMapper.toResponses(promptService.available(caller, spaceId));
  }
}
