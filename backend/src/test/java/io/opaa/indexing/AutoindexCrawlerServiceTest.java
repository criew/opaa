package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AutoindexCrawlerServiceTest {

  private final AutoindexCrawlerService service = new AutoindexCrawlerService();

  @Test
  void parsesTypicalDirectoryListing() {
    String html =
        """
        <table>
        <tr><th>Icon</th><th>Name</th><th>Last modified</th><th>Size</th></tr>
        <tr><td><img src="/icons/back.gif" alt="[PARENTDIR]"></td>\
        <td><a href="/">Parent Directory</a></td><td>&nbsp;</td><td>&nbsp;</td></tr>
        <tr><td><img src="/icons/folder.gif" alt="[DIR]"></td>\
        <td><a href="subdir/">subdir</a></td><td>2025-06-15 10:30</td><td>-</td></tr>
        <tr><td><img src="/icons/text.gif" alt="[TXT]"></td>\
        <td><a href="readme.txt">readme.txt</a></td><td>2025-06-14 09:00</td><td>1.2K</td></tr>
        <tr><td><img src="/icons/pdf.gif" alt="[   ]"></td>\
        <td><a href="report.pdf">report.pdf</a></td><td>2025-06-10 14:22</td><td>4.5M</td></tr>
        </table>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://example.com/files", 0);

    assertThat(entries).hasSize(3);

    // Directory entry
    assertThat(entries.get(0).name()).isEqualTo("subdir");
    assertThat(entries.get(0).url()).isEqualTo("https://example.com/files/subdir/");
    assertThat(entries.get(0).isDirectory()).isTrue();
    assertThat(entries.get(0).lastModified()).isEqualTo("2025-06-15 10:30");
    assertThat(entries.get(0).depth()).isZero();

    // Text file
    assertThat(entries.get(1).name()).isEqualTo("readme.txt");
    assertThat(entries.get(1).url()).isEqualTo("https://example.com/files/readme.txt");
    assertThat(entries.get(1).isDirectory()).isFalse();
    assertThat(entries.get(1).size()).isEqualTo("1.2K");

    // PDF file
    assertThat(entries.get(2).name()).isEqualTo("report.pdf");
  }

  @Test
  void skipsParentDirectoryByAltText() {
    String html =
        """
        <table>
        <tr><td><img alt="[PARENTDIR]"></td>\
        <td><a href="/">Parent Directory</a></td><td>&nbsp;</td><td>-</td></tr>
        <tr><td><img alt="[TXT]"></td>\
        <td><a href="file.txt">file.txt</a></td><td>2025-01-01</td><td>100</td></tr>
        </table>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://host/", 0);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().name()).isEqualTo("file.txt");
  }

  @Test
  void skipsSortingLinks() {
    String html =
        """
        <table>
        <tr><td><img alt="[ICO]"></td>\
        <td><a href="?C=N;O=D">Name</a></td><td><a href="?C=M;O=A">Last modified</a></td>\
        <td><a href="?C=S;O=A">Size</a></td></tr>
        <tr><td><img alt="[TXT]"></td>\
        <td><a href="file.txt">file.txt</a></td><td>2025-01-01</td><td>100</td></tr>
        </table>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://host/", 0);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().name()).isEqualTo("file.txt");
  }

  @Test
  void handlesAbsoluteUrls() {
    String html =
        """
        <table>
        <tr><td><img alt="[TXT]"></td>\
        <td><a href="https://cdn.example.com/file.txt">file.txt</a></td>\
        <td>2025-01-01</td><td>100</td></tr>
        </table>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://host/dir/", 0);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().url()).isEqualTo("https://cdn.example.com/file.txt");
  }

  @Test
  void resolvesRelativeUrlWithTrailingSlash() {
    assertThat(AutoindexCrawlerService.resolveUrl("https://host/dir", "file.txt"))
        .isEqualTo("https://host/dir/file.txt");

    assertThat(AutoindexCrawlerService.resolveUrl("https://host/dir/", "file.txt"))
        .isEqualTo("https://host/dir/file.txt");
  }

  @Test
  void buildsBasicAuthHeader() {
    String header = AutoindexCrawlerService.buildAuthHeader("admin", "secret");

    assertThat(header).startsWith("Basic ");
    assertThat(header).isEqualTo("Basic YWRtaW46c2VjcmV0");
  }

  @Test
  void returnsNullAuthHeaderForNullCredentials() {
    assertThat(AutoindexCrawlerService.buildAuthHeader(null, null)).isNull();
    assertThat(AutoindexCrawlerService.buildAuthHeader("user", null)).isNull();
    assertThat(AutoindexCrawlerService.buildAuthHeader(null, "pass")).isNull();
  }

  @Test
  void handlesDirectoryListingWithExtraColumns() {
    String html =
        """
        <table>
        <tr><td><img alt="[TXT]"></td>\
        <td><a href="file.txt">file.txt</a></td>\
        <td>2025-01-01</td><td>100</td><td>Some description</td></tr>
        </table>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://host/", 0);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().name()).isEqualTo("file.txt");
    assertThat(entries.getFirst().lastModified()).isEqualTo("2025-01-01");
    assertThat(entries.getFirst().size()).isEqualTo("100");
  }

  @Test
  void handlesCaseInsensitiveAltText() {
    String html =
        """
        <table>
        <tr><td><img alt="[dir]"></td>\
        <td><a href="subdir/">subdir</a></td><td>2025-01-01</td><td>-</td></tr>
        <tr><td><img alt="[Dir]"></td>\
        <td><a href="other/">other</a></td><td>2025-01-01</td><td>-</td></tr>
        </table>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://host/", 0);

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).isDirectory()).isTrue();
    assertThat(entries.get(1).isDirectory()).isTrue();
  }

  @Test
  void stripsNbspFromFields() {
    String html =
        """
        <table>
        <tr><td><img alt="[TXT]"></td>\
        <td><a href="file.txt">file.txt</a></td>\
        <td>&nbsp;2025-01-01&nbsp;</td><td>&nbsp;100&nbsp;</td></tr>
        </table>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://host/", 0);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().lastModified()).isEqualTo("2025-01-01");
    assertThat(entries.getFirst().size()).isEqualTo("100");
  }

  @Test
  void returnsEmptyListForNullHtml() {
    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(null, "https://host/", 0);

    assertThat(entries).isEmpty();
  }

  @Test
  void returnsEmptyListForInvalidHtml() {
    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory("<html><body>No table here</body></html>", "https://host/", 0);

    assertThat(entries).isEmpty();
  }

  // --- #550: link-based layouts (Apache without HTMLTable, nginx, <ul>) --------------------

  @Test
  void parsesApacheModAutoindexPreLayoutWithoutHtmlTable() {
    // Standard Apache mod_autoindex output when "IndexOptions HTMLTable" is NOT set - a <pre>
    // block with one icon+link per line, followed by date and size as trailing text (#550).
    String html =
        """
        <html><head><title>Index of /files/</title></head><body>
        <h1>Index of /files/</h1>
        <pre><img src="/icons/back.gif" alt="[PARENTDIR]"> <a href="/">Parent Directory</a>\
                             -
        <img src="/icons/folder.gif" alt="[DIR]"> <a href="subdir/">subdir/</a>\
                     15-Jun-2025 10:30    -
        <img src="/icons/text.gif" alt="[TXT]"> <a href="readme.txt">readme.txt</a>\
                  14-Jun-2025 09:00  1.2K
        <img src="/icons/pdf.gif" alt="[   ]"> <a href="report.pdf">report.pdf</a>\
                  10-Jun-2025 14:22  4.5M
        </pre>
        <hr></body></html>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://example.com/files", 0);

    assertThat(entries).hasSize(3);
    assertThat(entries.get(0).name()).isEqualTo("subdir");
    assertThat(entries.get(0).isDirectory()).isTrue();
    assertThat(entries.get(0).url()).isEqualTo("https://example.com/files/subdir/");
    assertThat(entries.get(1).name()).isEqualTo("readme.txt");
    assertThat(entries.get(1).isDirectory()).isFalse();
    assertThat(entries.get(1).size()).isEqualTo("1.2K");
    assertThat(entries.get(1).lastModified()).isEqualTo("14-Jun-2025 09:00");
    assertThat(entries.get(2).name()).isEqualTo("report.pdf");
  }

  @Test
  void parsesNginxAutoindexPreLayout() {
    // nginx "autoindex on" - a <pre> block without icons, parent link rendered as "../" (#550).
    String html =
        """
        <html><head><title>Index of /files/</title></head>
        <body>
        <h1>Index of /files/</h1><hr><pre><a href="../">../</a>
        <a href="subdir/">subdir/</a>\
                                                   15-Jun-2025 10:30                   -
        <a href="readme.txt">readme.txt</a>\
                                                14-Jun-2025 09:00                1234
        </pre><hr>
        </body></html>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://example.com/files", 0);

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).name()).isEqualTo("subdir");
    assertThat(entries.get(0).isDirectory()).isTrue();
    assertThat(entries.get(1).name()).isEqualTo("readme.txt");
    assertThat(entries.get(1).isDirectory()).isFalse();
    assertThat(entries.get(1).size()).isEqualTo("1234");
  }

  @Test
  void parsesApacheFancyIndexingOffUlLayout() {
    // Apache "IndexOptions -FancyIndexing" - a plain <ul> of links, no date/size at all (#550).
    String html =
        """
        <html><head><title>Index of /files/</title></head><body>
        <h1>Index of /files/</h1>
        <ul>
        <li><a href="/"> Parent Directory</a></li>
        <li><a href="subdir/"> subdir/</a></li>
        <li><a href="readme.txt"> readme.txt</a></li>
        </ul>
        </body></html>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://example.com/files", 0);

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).name()).isEqualTo("subdir");
    assertThat(entries.get(0).isDirectory()).isTrue();
    assertThat(entries.get(1).name()).isEqualTo("readme.txt");
    assertThat(entries.get(1).isDirectory()).isFalse();
  }

  @Test
  void parsesPythonHttpServerUlLayout() {
    // Python's http.server directory listing - a plain <ul>, page title "Directory listing
    // for ..." instead of Apache/nginx's "Index of ..." (#550).
    String html =
        """
        <!DOCTYPE HTML>
        <html lang="en">
        <head><meta charset="utf-8"><title>Directory listing for /files/</title></head>
        <body>
        <h1>Directory listing for /files/</h1>
        <hr>
        <ul>
        <li><a href="subdir/">subdir/</a></li>
        <li><a href="readme.txt">readme.txt</a></li>
        <li><a href="report.pdf">report.pdf</a></li>
        </ul>
        <hr>
        </body>
        </html>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://example.com/files", 0);

    assertThat(entries).hasSize(3);
    assertThat(entries.get(0).name()).isEqualTo("subdir");
    assertThat(entries.get(0).isDirectory()).isTrue();
    assertThat(entries.get(1).name()).isEqualTo("readme.txt");
    assertThat(entries.get(2).name()).isEqualTo("report.pdf");
  }

  @Test
  void looksLikeDirectoryListingRecognizesEveryLayout() {
    assertThat(service.looksLikeDirectoryListing("<table><tr><td>a</td></tr></table>")).isTrue();
    assertThat(service.looksLikeDirectoryListing("<pre><a href=\"a.txt\">a.txt</a></pre>"))
        .isTrue();
    assertThat(
            service.looksLikeDirectoryListing(
                "<html><head><title>Index of /files/</title></head>"
                    + "<body><ul><li><a href=\"a.txt\">a.txt</a></li></ul></body></html>"))
        .isTrue();
    assertThat(
            service.looksLikeDirectoryListing(
                "<html><head><title>Index of /empty/</title></head><body></body></html>"))
        .isTrue();
    assertThat(
            service.looksLikeDirectoryListing(
                "<html><head><title>Directory listing for /empty/</title></head>"
                    + "<body></body></html>"))
        .isTrue();
  }

  @Test
  void looksLikeDirectoryListingRejectsAnOrdinaryPage() {
    String html =
        """
        <html><head><title>Welcome</title></head>
        <body><nav><ul><li><a href="/about">About</a></li></ul></nav>
        <p>This is just a website, not a directory listing.</p></body></html>
        """;

    assertThat(service.looksLikeDirectoryListing(html)).isFalse();
  }

  @Test
  void skipsRowsWithTooFewCells() {
    String html =
        """
        <table>
        <tr><td><img alt="[TXT]"></td><td>only two cells</td></tr>
        <tr><td><img alt="[TXT]"></td>\
        <td><a href="file.txt">file.txt</a></td>\
        <td>2025-01-01</td><td>100</td></tr>
        </table>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://host/", 0);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().name()).isEqualTo("file.txt");
  }
}
