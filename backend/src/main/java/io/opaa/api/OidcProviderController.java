package io.opaa.api;

import io.opaa.api.dto.OidcProviderOrderRequest;
import io.opaa.api.dto.OidcProviderRequest;
import io.opaa.api.dto.OidcProviderResponse;
import io.opaa.api.dto.OidcProviderTestRequest;
import io.opaa.api.dto.OidcProviderTestResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderConnectionTester;
import io.opaa.auth.oidc.OidcProviderRegistry;
import io.opaa.auth.oidc.OidcProviderService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for the identity providers (#1329, ADR-0025), {@code SYSTEM_ADMIN} only - the same
 * access bar and {@code @Caller} pattern as {@link LlmModelController}. A provider is a public
 * client: no response carries a secret because none exists. Every response carries the registry's
 * view of the provider ({@link OidcProviderRegistry#healthOf}), so the Anbieterverwaltung can show
 * a provider whose decoder could not be built. Disabling or deleting the last enabled OIDC provider
 * needs {@code acknowledgeLastProvider=true} (ADR-0033, Entscheidung 4); the LOCAL row's
 * enable/disable is the switch of the local account management.
 */
@RestController
@RequestMapping("/api/v1/admin/oidc-providers")
public class OidcProviderController {

  private final OidcProviderService providerService;
  private final OidcProviderConnectionTester connectionTester;
  private final OidcProviderRegistry registry;

  public OidcProviderController(
      OidcProviderService providerService,
      OidcProviderConnectionTester connectionTester,
      OidcProviderRegistry registry) {
    this.providerService = providerService;
    this.connectionTester = connectionTester;
    this.registry = registry;
  }

  private OidcProviderResponse toResponse(OidcProvider provider) {
    return OidcProviderResponseMapper.toResponse(provider, registry.healthOf(provider.getId()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public List<OidcProviderResponse> listProviders() {
    return providerService.listProviders().stream().map(this::toResponse).toList();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping
  public ResponseEntity<OidcProviderResponse> createProvider(
      @Valid @RequestBody OidcProviderRequest request, @Caller CurrentUser caller) {
    OidcProvider created =
        providerService.createProvider(
            caller.organizationId(), caller.id(), OidcProviderResponseMapper.toDraft(request));
    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/{providerId}")
  public OidcProviderResponse getProvider(@PathVariable UUID providerId) {
    return toResponse(providerService.getProvider(providerId));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping("/{providerId}")
  public OidcProviderResponse updateProvider(
      @PathVariable UUID providerId,
      @Valid @RequestBody OidcProviderRequest request,
      @Caller CurrentUser caller) {
    return toResponse(
        providerService.updateProvider(
            caller.organizationId(),
            caller.id(),
            providerId,
            OidcProviderResponseMapper.toDraft(request)));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/{providerId}")
  public ResponseEntity<Void> deleteProvider(
      @PathVariable UUID providerId,
      @RequestParam(defaultValue = "false") boolean acknowledgeLastProvider,
      @Caller CurrentUser caller) {
    providerService.deleteProvider(
        caller.organizationId(), caller.id(), providerId, acknowledgeLastProvider);
    return ResponseEntity.noContent().build();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{providerId}/enable")
  public OidcProviderResponse enableProvider(
      @PathVariable UUID providerId, @Caller CurrentUser caller) {
    return toResponse(
        providerService.setEnabled(caller.organizationId(), caller.id(), providerId, true));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{providerId}/disable")
  public OidcProviderResponse disableProvider(
      @PathVariable UUID providerId,
      @RequestParam(defaultValue = "false") boolean acknowledgeLastProvider,
      @Caller CurrentUser caller) {
    return toResponse(
        providerService.setEnabled(
            caller.organizationId(), caller.id(), providerId, false, acknowledgeLastProvider));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{providerId}/default")
  public OidcProviderResponse makeDefault(
      @PathVariable UUID providerId, @Caller CurrentUser caller) {
    return toResponse(
        providerService.makeDefault(caller.organizationId(), caller.id(), providerId));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping("/order")
  public List<OidcProviderResponse> reorder(
      @Valid @RequestBody OidcProviderOrderRequest request, @Caller CurrentUser caller) {
    return providerService
        .reorder(caller.organizationId(), caller.id(), request.getProviderIds())
        .stream()
        .map(this::toResponse)
        .toList();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/test")
  public OidcProviderTestResponse testProvider(
      @Valid @RequestBody OidcProviderTestRequest request) {
    OidcProviderConnectionTester.TestOutcome outcome =
        connectionTester.test(request.getIssuerUri(), request.getJwkSetUri());
    return new OidcProviderTestResponse(outcome.success(), outcome.message());
  }
}
