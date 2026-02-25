package io.opaa.api;

import io.opaa.api.dto.QueryRequest;
import io.opaa.api.dto.QueryResponse;
import io.opaa.query.QueryService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!mock")
@RestController
@RequestMapping("/api/v1")
public class QueryController {

  private final QueryService queryService;

  public QueryController(QueryService queryService) {
    this.queryService = queryService;
  }

  @PostMapping("/query")
  public QueryResponse query(@Valid @RequestBody QueryRequest request) {
    return queryService.query(request.question());
  }
}
