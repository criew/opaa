package io.opaa.query.web;

import io.opaa.api.dto.MetadataFilterOptionsResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import io.opaa.query.filter.MetadataFilterOptionsService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the filter interface of the chat offers (#1070). Absent while the metadata feature is off
 * (ADR-0044); the question itself stays with {@link QueryController}.
 */
@RestController
@ConditionalOnFeature(Feature.METADATA)
@RequestMapping("/api/v1")
public class MetadataFilterOptionsController {

  private final MetadataFilterOptionsService metadataFilterOptionsService;

  public MetadataFilterOptionsController(
      MetadataFilterOptionsService metadataFilterOptionsService) {
    this.metadataFilterOptionsService = metadataFilterOptionsService;
  }

  /**
   * The Füllstand and offered values of the filterable core fields in the caller's search scope,
   * resolved with the same rules as {@link QueryController#query}.
   */
  @GetMapping("/search/metadata-filter-options")
  public MetadataFilterOptionsResponse metadataFilterOptions(
      @RequestParam(required = false) UUID chatId,
      @RequestParam(required = false) UUID spaceId,
      @RequestParam(required = false, defaultValue = "true") boolean useKnowledge,
      @RequestParam(required = false) List<UUID> libraryIds,
      @Caller CurrentUser caller) {
    return MetadataFilterOptionsResponseMapper.toResponse(
        metadataFilterOptionsService.optionsFor(
            caller, chatId, spaceId, useKnowledge, libraryIds == null ? List.of() : libraryIds));
  }
}
