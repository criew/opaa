package io.opaa.api;

import io.opaa.api.dto.SearchHitContentResponse;
import io.opaa.api.dto.SearchRequest;
import io.opaa.api.dto.SearchResponse;
import io.opaa.api.dto.SearchableLibrary;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.search.PassageFetchService;
import io.opaa.search.SearchService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reading path without generation (#1720): hits instead of an answer, and the text behind one
 * hit.
 *
 * <p>A regular endpoint of this API, open to every signed-in person with exactly the rights check
 * {@code POST /api/v1/query} applies. It deliberately does <b>not</b> hang on the external-access
 * switch: that switch is the emergency stop of the external-access channel, not of the search
 * endpoint - for a token call it applies, and #1718 enforces it where the token is read.
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

  private final SearchService searchService;
  private final PassageFetchService passageFetchService;

  public SearchController(SearchService searchService, PassageFetchService passageFetchService) {
    this.searchService = searchService;
    this.passageFetchService = passageFetchService;
  }

  @PostMapping
  public SearchResponse search(
      @Valid @RequestBody SearchRequest request, @Caller CurrentUser caller) {
    return SearchResponseMapper.toResponse(
        searchService.search(
            caller,
            request.getQuestion(),
            request.getLibraryIds(),
            MetadataFilterMapper.toDomain(request.getMetadataFilter()),
            request.getMaxHits()));
  }

  /**
   * The libraries this caller may search - the only place a foreign tool learns the extent of its
   * access, and the basis of {@code list_libraries} of the MCP server (#1721).
   */
  @GetMapping("/libraries")
  public List<SearchableLibrary> libraries(@Caller CurrentUser caller) {
    return SearchResponseMapper.toLibraries(searchService.libraries(caller));
  }

  @GetMapping("/hits/{hitId}")
  public SearchHitContentResponse fetchHit(
      @PathVariable String hitId,
      @RequestParam(required = false, defaultValue = "false") boolean full,
      @Caller CurrentUser caller) {
    return SearchResponseMapper.toResponse(passageFetchService.fetch(caller, hitId, full));
  }
}
