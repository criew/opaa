package io.opaa.template.web;

import io.opaa.api.dto.DocumentTemplateSettingsRequest;
import io.opaa.api.dto.DocumentTemplateSettingsResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.template.DocumentTemplateSettingsService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The installation switch "Vorlagen" (system admin only). */
@RestController
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequestMapping("/api/v1/system/document-templates")
public class DocumentTemplateSettingsController {

  private final DocumentTemplateSettingsService settingsService;

  public DocumentTemplateSettingsController(DocumentTemplateSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @GetMapping
  public DocumentTemplateSettingsResponse getDocumentTemplateSettings(@Caller CurrentUser caller) {
    return DocumentTemplateResponseMapper.toSettingsResponse(settingsService.get(caller));
  }

  @PutMapping
  public DocumentTemplateSettingsResponse updateDocumentTemplateSettings(
      @Valid @RequestBody DocumentTemplateSettingsRequest request, @Caller CurrentUser caller) {
    return DocumentTemplateResponseMapper.toSettingsResponse(
        settingsService.update(request.getEnabled(), caller));
  }
}
