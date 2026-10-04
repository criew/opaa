package io.opaa.library.web;

import io.opaa.api.dto.SourceBrowseRequest;
import io.opaa.api.dto.SourceBrowseResponse;
import io.opaa.api.dto.SourceTypeDescriptor;
import io.opaa.api.types.Capability;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.ConnectorReleaseService;
import io.opaa.connection.ConnectorReleaseService.TypeCreation;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.SourceType;
import io.opaa.library.SourceConnectionTestService;
import io.opaa.permission.CapabilityService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The source types this installation serves, straight from the connector registry, each with
 * whether the caller may create a library of it now, and what a source of one offers for selection
 * before it is saved (ADR-0038).
 */
@RestController
@RequestMapping("/api/v1/source-types")
public class SourceTypeController {

  private final SourceConnectorRegistry connectors;
  private final SourceConnectionTestService sourceConnectionTestService;
  private final ConnectorReleaseService connectorRelease;
  private final CapabilityService capabilities;

  public SourceTypeController(
      SourceConnectorRegistry connectors,
      SourceConnectionTestService sourceConnectionTestService,
      ConnectorReleaseService connectorRelease,
      CapabilityService capabilities) {
    this.connectors = connectors;
    this.sourceConnectionTestService = sourceConnectionTestService;
    this.connectorRelease = connectorRelease;
    this.capabilities = capabilities;
  }

  @GetMapping
  public List<SourceTypeDescriptor> listSourceTypes(@Caller CurrentUser caller) {
    Map<SourceType, TypeCreation> creations = connectorRelease.typeCreations(caller);
    boolean uploads = capabilities.hasCapability(caller, Capability.CREATE_LIBRARY);
    TypeCreation upload =
        new TypeCreation(
            uploads,
            uploads,
            false,
            false,
            uploads ? null : CapabilityService.missing(Capability.CREATE_LIBRARY));
    return connectors.descriptors().stream()
        .map(
            descriptor ->
                SourceConnectionTestResponseMapper.toResponse(
                    descriptor,
                    connectors.browser(descriptor.type()).isPresent(),
                    descriptor.uploads() ? upload : creations.get(descriptor.type())))
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
