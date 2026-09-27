package io.opaa.api;

import io.opaa.api.dto.SourceBrowseRequest;
import io.opaa.api.dto.SourceBrowseResponse;
import io.opaa.api.dto.SourceTypeDescriptor;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.library.SourceConnectionTestService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The source types this installation serves, straight from the connector registry, and what a
 * source of one offers for selection before it is saved (ADR-0038).
 */
@RestController
@RequestMapping("/api/v1/source-types")
public class SourceTypeController {

  private final SourceConnectorRegistry connectors;
  private final SourceConnectionTestService sourceConnectionTestService;

  public SourceTypeController(
      SourceConnectorRegistry connectors, SourceConnectionTestService sourceConnectionTestService) {
    this.connectors = connectors;
    this.sourceConnectionTestService = sourceConnectionTestService;
  }

  @GetMapping
  public List<SourceTypeDescriptor> listSourceTypes() {
    return connectors.descriptors().stream()
        .map(
            descriptor ->
                SourceConnectionTestResponseMapper.toResponse(
                    descriptor, connectors.browser(descriptor.type()).isPresent()))
        .toList();
  }

  /**
   * Same permission bar (#1856), stored-credentials fallback and rate limit as the connection test;
   * credentials travel in the body and never come back.
   */
  @PostMapping("/{sourceType}/browse")
  public SourceBrowseResponse browseSource(
      @PathVariable String sourceType,
      @Valid @RequestBody SourceBrowseRequest request,
      @Caller CurrentUser caller) {
    return SourceConnectionTestResponseMapper.toResponse(
        sourceConnectionTestService.browse(
            SourceConnectionTestResponseMapper.toDomain(sourceType, request), caller));
  }
}
