package io.opaa.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;

/**
 * The crafted seeds are the same bytes on every load, so a campaign seed names the same inputs: the
 * trailer ID of a PDF is fixed, and so are the times and dates of every package, also of one
 * embedded in a package or in an OLE2 object of a package.
 */
class HostileSeedsTest {

  private static final Pattern PACKAGE_DATE =
      Pattern.compile("<dcterms:(?:created|modified)[^>]*+>([^<]*+)</dcterms:");

  @Test
  void everySeedIsTheSameBytesOnEveryLoad() {
    Map<String, byte[]> first = byName(HostileSeeds.seeds());
    Map<String, byte[]> second = byName(HostileSeeds.seeds());

    assertThat(second.keySet()).isEqualTo(first.keySet());
    assertThat(first.keySet())
        .as("seeds whose bytes differ between two loads")
        .filteredOn(name -> !Arrays.equals(first.get(name), second.get(name)))
        .isEmpty();
  }

  @Test
  void everyPackageAlsoWhenEmbeddedCarriesTheFixedTimeAndDates() throws IOException {
    List<String> packages = new ArrayList<>();
    for (FuzzCorpus.Seed seed : HostileSeeds.seeds()) {
      assertFixed(seed.name(), seed.bytes(), packages, false);
    }

    assertThat(packages)
        .as("packages embedded in OLE2 objects of a package, which the check must reach")
        .anyMatch(name -> name.contains("oleObject") && name.endsWith("!docProps/core.xml"));
  }

  /** Descends into OLE2 only inside a package: a crafted top-level OLE2 file may be unreadable. */
  private static void assertFixed(
      String name, byte[] bytes, List<String> packages, boolean embedded) throws IOException {
    if (embedded && FileMagic.valueOf(bytes) == FileMagic.OLE2) {
      try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
        assertFixed(name, fs.getRoot(), packages);
      }
      return;
    }
    if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
      return;
    }
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        String path = name + "!" + entry.getName();
        byte[] data = zip.readAllBytes();
        assertThat(entry.getTimeLocal()).as(path).isEqualTo(Mutators.ENTRY_TIME);
        if (entry.getName().equals("docProps/core.xml")) {
          packages.add(path);
          PACKAGE_DATE
              .matcher(new String(data, StandardCharsets.UTF_8))
              .results()
              .forEach(date -> assertThat(date.group(1)).as(path).isEqualTo(Mutators.FIXED_DATE));
        }
        assertFixed(path, data, packages, true);
      }
    }
  }

  private static void assertFixed(String name, DirectoryEntry directory, List<String> packages)
      throws IOException {
    for (Entry entry : directory) {
      String path = name + "/" + entry.getName();
      if (entry instanceof DirectoryEntry child) {
        assertFixed(path, child, packages);
      } else if (entry instanceof DocumentEntry document) {
        try (InputStream in = new DocumentInputStream(document)) {
          assertFixed(path, in.readNBytes(document.getSize()), packages, true);
        }
      }
    }
  }

  private static Map<String, byte[]> byName(List<FuzzCorpus.Seed> seeds) {
    Map<String, byte[]> byName = new TreeMap<>();
    seeds.forEach(seed -> byName.put(seed.name(), seed.bytes()));
    return byName;
  }
}
