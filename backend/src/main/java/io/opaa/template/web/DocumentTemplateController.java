package io.opaa.template.web;

import io.opaa.api.dto.DocumentTemplateAvailabilityResponse;
import io.opaa.api.dto.DocumentTemplateResponse;
import io.opaa.api.dto.DocumentTemplateUpdateRequest;
import io.opaa.api.types.AssetOwnerType;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.template.DocumentTemplateCreation;
import io.opaa.template.DocumentTemplateService;
import io.opaa.template.DocumentTemplateSettingsService;
import io.opaa.template.DocumentTemplateUpload;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Document templates. Grants, the Herleitung and the space association of a template are the asset
 * shell's, under {@code /api/v1/assets/DOCUMENT_TEMPLATE/{assetId}}. Every operation but the
 * availability answers 404 while the switch "Vorlagen" is off.
 */
@RestController
@RequestMapping("/api/v1/document-templates")
public class DocumentTemplateController {

  private static final MediaType DOCX =
      MediaType.parseMediaType(
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

  private final DocumentTemplateService templateService;
  private final DocumentTemplateSettingsService settingsService;

  public DocumentTemplateController(
      DocumentTemplateService templateService, DocumentTemplateSettingsService settingsService) {
    this.templateService = templateService;
    this.settingsService = settingsService;
  }

  @GetMapping("/availability")
  public DocumentTemplateAvailabilityResponse getDocumentTemplateAvailability() {
    return new DocumentTemplateAvailabilityResponse(settingsService.isEnabled());
  }

  @GetMapping
  public List<DocumentTemplateResponse> listDocumentTemplates(@Caller CurrentUser caller) {
    return DocumentTemplateResponseMapper.toResponses(templateService.list(caller));
  }

  @PostMapping
  public ResponseEntity<DocumentTemplateResponse> createDocumentTemplate(
      @RequestParam("file") MultipartFile file,
      @RequestParam("name") String name,
      @RequestParam(value = "description", required = false) String description,
      @RequestParam(value = "ownerType", required = false) AssetOwnerType ownerType,
      @RequestParam(value = "ownerId", required = false) UUID ownerId,
      @Caller CurrentUser caller) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            DocumentTemplateResponseMapper.toResponse(
                templateService.create(
                    new DocumentTemplateCreation(
                        name, description, ownerType, ownerId, upload(file)),
                    caller)));
  }

  @GetMapping("/{documentTemplateId}")
  public DocumentTemplateResponse getDocumentTemplate(
      @PathVariable UUID documentTemplateId, @Caller CurrentUser caller) {
    return DocumentTemplateResponseMapper.toResponse(
        templateService.get(documentTemplateId, caller));
  }

  @PutMapping("/{documentTemplateId}")
  public DocumentTemplateResponse updateDocumentTemplate(
      @PathVariable UUID documentTemplateId,
      @Valid @RequestBody DocumentTemplateUpdateRequest request,
      @Caller CurrentUser caller) {
    return DocumentTemplateResponseMapper.toResponse(
        templateService.update(
            documentTemplateId, request.getName(), request.getDescription(), caller));
  }

  @DeleteMapping("/{documentTemplateId}")
  public ResponseEntity<Void> deleteDocumentTemplate(
      @PathVariable UUID documentTemplateId, @Caller CurrentUser caller) {
    templateService.delete(documentTemplateId, caller);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{documentTemplateId}/file")
  public ResponseEntity<byte[]> downloadDocumentTemplateFile(
      @PathVariable UUID documentTemplateId, @Caller CurrentUser caller) {
    DocumentTemplateService.TemplateFile file =
        templateService.download(documentTemplateId, caller);
    return ResponseEntity.ok()
        .contentType(DOCX)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(file.content());
  }

  @PutMapping("/{documentTemplateId}/file")
  public DocumentTemplateResponse replaceDocumentTemplateFile(
      @PathVariable UUID documentTemplateId,
      @RequestParam("file") MultipartFile file,
      @Caller CurrentUser caller) {
    return DocumentTemplateResponseMapper.toResponse(
        templateService.replaceFile(documentTemplateId, upload(file), caller));
  }

  private static DocumentTemplateUpload upload(MultipartFile file) {
    return new DocumentTemplateUpload() {
      @Override
      public String fileName() {
        return file.getOriginalFilename();
      }

      @Override
      public long sizeBytes() {
        return file.getSize();
      }

      @Override
      public InputStream open() throws IOException {
        return file.getInputStream();
      }
    };
  }
}
