package io.opaa.linktarget.web;

import io.opaa.api.dto.LinkTargets;
import io.opaa.linktarget.LinkTargetSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The allowed link targets for every signed-in person: the interface needs them to decide which
 * links of an answer it renders clickable. Changing them is {@link SystemLinkTargetController}.
 */
@RestController
@RequestMapping("/api/v1/link-targets")
public class LinkTargetController {

  private final LinkTargetSettingsService settings;

  public LinkTargetController(LinkTargetSettingsService settings) {
    this.settings = settings;
  }

  @GetMapping
  public LinkTargets getLinkTargets() {
    return LinkTargetResponseMapper.toResponse(settings.current());
  }
}
