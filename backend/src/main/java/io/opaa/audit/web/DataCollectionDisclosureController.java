package io.opaa.audit.web;

import io.opaa.api.dto.DataCollectionDisclosure;
import io.opaa.audit.DataCollectionDisclosureService;
import io.opaa.audit.DataCollectionDisclosureService.Format;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.compliance.InstanceDisclosureRenderer;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The disclosure of the data collection with the configuration export of this instance, as JSON for
 * tools and as Markdown for reading. Deliberately no {@code @PreAuthorize}: {@link
 * DataCollectionDisclosureService} checks the role so that a refusal is logged as well.
 */
@RestController
@RequestMapping("/api/v1/admin/compliance/data-collection-disclosure")
public class DataCollectionDisclosureController {

  /** The media type of the readable form. */
  static final String TEXT_MARKDOWN = "text/markdown;charset=UTF-8";

  private final DataCollectionDisclosureService disclosureService;

  public DataCollectionDisclosureController(DataCollectionDisclosureService disclosureService) {
    this.disclosureService = disclosureService;
  }

  @GetMapping
  public DataCollectionDisclosure getDataCollectionDisclosure(
      @Caller CurrentUser caller,
      @RequestParam(name = "from", required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam(name = "to", required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
    return DataCollectionDisclosureResponseMapper.toResponse(
        disclosureService.disclose(
            caller.organizationId(), caller.id(), caller.systemRole(), Format.JSON, from, to));
  }

  @GetMapping(value = "/markdown", produces = TEXT_MARKDOWN)
  public String getDataCollectionDisclosureMarkdown(
      @Caller CurrentUser caller,
      @RequestParam(name = "from", required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam(name = "to", required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
    return InstanceDisclosureRenderer.render(
        disclosureService.disclose(
            caller.organizationId(), caller.id(), caller.systemRole(), Format.MARKDOWN, from, to));
  }
}
