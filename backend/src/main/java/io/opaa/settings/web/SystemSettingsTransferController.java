package io.opaa.settings.web;

import io.opaa.api.dto.SettingsDocument;
import io.opaa.api.dto.SettingsImportResult;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ValidationException;
import io.opaa.settings.SettingsTransferService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Export, preview and import of the installation's settings, system admin only. Both intakes read
 * the body themselves, bounded to {@link #MAX_BODY_BYTES}, so a document cannot grow without limit
 * before it is parsed, and parse it strictly: a key twice or an unknown field is refused.
 */
@RestController
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemSettingsTransferController {

  static final int MAX_BODY_BYTES = 256 * 1024;

  private static final String TOO_LARGE = "Die Datei ist größer als 256 KiB.";

  private final SettingsTransferService transfer;
  private final JsonMapper jsonMapper;

  public SystemSettingsTransferController(SettingsTransferService transfer, JsonMapper jsonMapper) {
    this.transfer = transfer;
    this.jsonMapper =
        jsonMapper
            .rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
  }

  @GetMapping("/api/v1/system/settings/export")
  public SettingsDocument exportSettings(@Caller CurrentUser caller) {
    return SettingsTransferResponseMapper.toResponse(transfer.export(caller));
  }

  @PostMapping(
      value = "/api/v1/system/settings/import/preview",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public SettingsImportResult previewSettingsImport(
      @Caller CurrentUser caller, HttpServletRequest request) throws IOException {
    return SettingsTransferResponseMapper.toResponse(
        transfer.preview(caller, document(readBounded(request))));
  }

  @PostMapping(
      value = "/api/v1/system/settings/import",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public SettingsImportResult importSettings(@Caller CurrentUser caller, HttpServletRequest request)
      throws IOException {
    return SettingsTransferResponseMapper.toResponse(
        transfer.importDocument(caller, document(readBounded(request))));
  }

  private io.opaa.settings.SettingsDocument document(byte[] body) {
    try {
      return SettingsTransferResponseMapper.toDomain(
          jsonMapper.readValue(body, SettingsDocument.class));
    } catch (JacksonException unreadable) {
      throw new ValidationException("Die Datei ist kein lesbares Einstellungsdokument von OPAA.");
    }
  }

  /** Rejects by the declared length first, then by what actually arrives (chunked senders). */
  static byte[] readBounded(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      throw new PayloadTooLargeException(TOO_LARGE);
    }
    try (InputStream in = request.getInputStream()) {
      byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
      if (body.length > MAX_BODY_BYTES) {
        throw new PayloadTooLargeException(TOO_LARGE);
      }
      return body;
    }
  }
}
