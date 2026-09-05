package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CrawlPropertiesTest {

  @Test
  void anUnsetEntrySizeCapFallsBackToTheDefaultInsteadOfRejectingEveryEntry() {
    // 0 means "not configured", not "no entry may exceed zero bytes" - an installation
    // that never sets the property must keep indexing, under the documented default.
    assertThat(new CrawlProperties(10, 5000, 0).maxFileSizeBytes())
        .isEqualTo(CrawlProperties.DEFAULT_MAX_FILE_SIZE_BYTES);
  }

  @Test
  void theDefaultEntrySizeCapStaysBelowTheMarkLimitOfTikasPoifsDetection() {
    // A cap at or above the markLimit would leave the case the download cap exists for open: an
    // OLE2 entry
    // transferred in full only to be rejected afterwards by that detection limit. 128 MiB is
    // POIFSContainerDetector's own default (Tika 3.3.2, the pinned version);
    // Tika exposes it only through a package-private field, hence the literal here.
    assertThat(CrawlProperties.DEFAULT_MAX_FILE_SIZE_BYTES).isLessThan(128L * 1024 * 1024);
  }

  @Test
  void aNegativeEntrySizeCapIsRejectedOutright() {
    assertThatThrownBy(() -> new CrawlProperties(10, 5000, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxFileSizeBytes");
  }
}
