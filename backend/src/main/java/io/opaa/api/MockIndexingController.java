package io.opaa.api;

import io.opaa.api.dto.IndexingStatus;
import io.opaa.api.dto.IndexingStatusResponse;
import io.opaa.api.dto.IndexingTriggerRequest;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("mock")
@RestController
@RequestMapping("/api/v1/indexing")
public class MockIndexingController {

  @PostMapping("/trigger")
  public IndexingStatusResponse triggerIndexing(
      @RequestBody(required = false) IndexingTriggerRequest request) {
    return response(
        IndexingStatus.COMPLETED, 42, 42, 0, "Indexing completed successfully", Instant.now());
  }

  @GetMapping("/status")
  public IndexingStatusResponse getIndexingStatus() {
    return response(
        IndexingStatus.COMPLETED, 42, 42, 0, "Indexing completed successfully", Instant.now());
  }

  private IndexingStatusResponse response(
      IndexingStatus status,
      int documentCount,
      int totalDocuments,
      int documentsSkipped,
      String message,
      Instant timestamp) {
    IndexingStatusResponse response =
        new IndexingStatusResponse(
            status, documentCount, totalDocuments, documentsSkipped, timestamp);
    response.setMessage(message);
    return response;
  }
}
