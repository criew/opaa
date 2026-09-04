package io.opaa.api;

import io.opaa.api.dto.MetadataFilterOptionsResponse;
import io.opaa.api.dto.QueryRequest;
import io.opaa.api.dto.QueryResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.query.MetadataFilterOptionsService;
import io.opaa.query.QueryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class QueryController {

  private final QueryService queryService;
  private final MetadataFilterOptionsService metadataFilterOptionsService;

  public QueryController(
      QueryService queryService, MetadataFilterOptionsService metadataFilterOptionsService) {
    this.queryService = queryService;
    this.metadataFilterOptionsService = metadataFilterOptionsService;
  }

  @PostMapping("/query")
  public QueryResponse query(@Valid @RequestBody QueryRequest request, @Caller CurrentUser caller) {
    boolean useKnowledge = request.getUseKnowledge() == null || request.getUseKnowledge();
    return QueryResponseMapper.toResponse(
        queryService.query(
            request.getQuestion(),
            request.getChatId(),
            caller,
            useKnowledge,
            request.getLibraryIds(),
            MetadataFilterMapper.toDomain(request.getMetadataFilter())));
  }

  /**
   * The Füllstand and offered values of the filterable core fields in the caller's search scope
   * (#1070), resolved with the same rules as {@link #query}.
   */
  @GetMapping("/search/metadata-filter-options")
  public MetadataFilterOptionsResponse metadataFilterOptions(
      @RequestParam(required = false) UUID chatId,
      @RequestParam(required = false, defaultValue = "true") boolean useKnowledge,
      @RequestParam(required = false) List<UUID> libraryIds,
      @Caller CurrentUser caller) {
    return MetadataFilterOptionsResponseMapper.toResponse(
        metadataFilterOptionsService.optionsFor(
            caller, chatId, useKnowledge, libraryIds == null ? List.of() : libraryIds));
  }
}
