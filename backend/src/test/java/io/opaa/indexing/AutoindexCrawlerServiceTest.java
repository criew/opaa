package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutoindexCrawlerServiceTest {

  // Pure parsing coverage only (no .crawl() call in this class) - target validation is exercised
  // on its own dedicated stand (TargetAddressValidatorTest).
  private final AutoindexCrawlerService service =
      new AutoindexCrawlerService(TargetAddressValidator.disabled());

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
  void derivesNameFromHrefWhenTheDisplayedNameIsTruncatedInHtmlTableLayout() {
    // #229 (validating the Rheinfurt demo corpus - realistic, long, hyphenated file names - against
    // this format, docs/features/demo-instance.md's own "erprobte Empfehlung") surfaced that the
    // #550 review, finding 4 fix (see derivesNameFromHrefWhenTheDisplayedNameIsTruncated above) was
    // only ever applied to the link-based layouts (parseLinkBasedLayout) - Apache's "IndexOptions
    // FancyIndexing HTMLTable" truncates the *displayed* name exactly the same way (rendered with a
    // "..&gt;" suffix) and was never covered: parseHtmlTableLayout used the anchor's link text
    // outright, silently losing the file extension and dropping every long-named entry from
    // SupportedDocumentFormats for the very listing format this project recommends.
    String html =
        """
        <table>
        <tr><td><img alt="[   ]"></td>\
        <td><a href="a-quite-long-report-file-name-example.pdf">a-quite-long-repo..&gt;</a></td>\
        <td>2025-06-10 14:22</td><td>4.5M</td></tr>
        </table>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://example.com/files", 0);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().name()).isEqualTo("a-quite-long-report-file-name-example.pdf");
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
  void handlesAbsoluteUrlsOnTheSameOrigin() {
    String html =
        """
        <table>
        <tr><td><img alt="[TXT]"></td>\
        <td><a href="https://host/dir/file.txt">file.txt</a></td>\
        <td>2025-01-01</td><td>100</td></tr>
        </table>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://host/dir/", 0);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().url()).isEqualTo("https://host/dir/file.txt");
  }

  @Test
  void skipsAbsoluteUrlsOnAForeignOrigin() {
    // #550 review, finding 2: an absolute href pointing at a different origin must never be
    // followed - the Authorization header (built from this source configuration's own
    // credentials) would otherwise be sent to a host it was never meant for.
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

    assertThat(entries).isEmpty();
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
  void ordinaryHomepageIsNotTreatedAsADirectoryListing() {
    // #550 review, finding 1: without this gate, an ordinary homepage would be crawled as a
    // directory too - every trailing-slash link becomes a DIR entry crawl() then recurses into,
    // unbounded, so a same-origin navigation cycle (a page linking back to a variant of itself)
    // would recurse forever.
    String html =
        """
        <html><head><title>Welcome</title></head>
        <body>
        <nav><ul><li><a href="/about/">About</a></li><li><a href="/blog/">Blog</a></li></ul></nav>
        <p>Just a website, not a directory listing.</p>
        </body></html>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://example.com/", 0);

    assertThat(entries).isEmpty();
  }

  @Test
  void linkBasedLayoutSkipsLinksOutsideBaseUrlAndForeignOrigins() {
    // #550 review, findings 1 and 2 together: the link-based fallback only trusts a link if it
    // both stays on baseUrl's origin (no credential leak to a foreign host) and resolves
    // underneath baseUrl itself (no wandering into unrelated same-origin pages a listing happens
    // to link to, which is exactly what would let an ordinary page slip past the listing gate and
    // recurse without bound).
    String html =
        """
        <html><head><title>Index of /files/</title></head><body>
        <pre><a href="https://cdn.example.com/other.txt">other.txt</a>\
                  10-Jun-2025 14:22  1K
        <a href="https://example.com/elsewhere/other2.txt">other2.txt</a>\
                  10-Jun-2025 14:22  1K
        <a href="readme.txt">readme.txt</a>\
                  10-Jun-2025 14:22  1K
        </pre></body></html>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://example.com/files", 0);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().name()).isEqualTo("readme.txt");
  }

  // --- #836 PR review ("Mitnahme"): relative hrefs must not escape above baseUrl -------------

  @Test
  void tableLayoutDoesNotEscapeAboveBaseUrlViaARelativeParentLink() {
    // parseHtmlTableLayout had no under-baseUrl check at all for relative hrefs (unlike
    // parseLinkBasedLayout below) - href="../" resolves, via resolveUrl's naive baseUrl+relative
    // concatenation, to a URL that only reveals it climbed back out of baseUrl's own subtree once
    // actually normalized.
    String html =
        """
        <table>
        <tr><td><img alt="[DIR]"></td><td><a href="../">../</a></td><td>2025-01-01</td><td>-</td></tr>
        <tr><td><img alt="[TXT]"></td><td><a href="file.txt">file.txt</a></td><td>2025-01-01</td><td>1</td></tr>
        </table>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://example.com/files/sub/", 0);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().name()).isEqualTo("file.txt");
  }

  @Test
  void linkBasedLayoutDoesNotEscapeAboveBaseUrlViaARelativePathThatNormalizesOutside() {
    // A relative href's *raw* resolved URL always starts with baseUrl by construction (resolveUrl
    // simply concatenates), no matter how many ".." segments it carries - so the pre-#836
    // startsWith(normalizedBaseUrl) check on that raw string was vacuous for every relative href.
    // Only normalizing first (staysUnderBase) actually catches an escape like this one.
    String html =
        """
        <html><head><title>Index of /files/sub/</title></head><body>
        <pre><a href="sibling/../../escape/">escape</a>\
                  10-Jun-2025 14:22  1K
        <a href="readme.txt">readme.txt</a>\
                  10-Jun-2025 14:22  1K
        </pre></body></html>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://example.com/files/sub", 0);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().name()).isEqualTo("readme.txt");
  }

  @Test
  void derivesNameFromHrefWhenTheDisplayedNameIsTruncated() {
    // #550 review, finding 4: Apache's "IndexOptions NameWidth" truncates only the *displayed*
    // name (rendered with a ".." suffix); the href always carries the full, URL-encoded file
    // name - using the link text here would lose the file extension and drop the entry from
    // SupportedDocumentFormats.
    String html =
        """
        <html><head><title>Index of /files/</title></head><body>
        <pre><a href="a-quite-long-report-file-name-example.pdf">a-quite-long-repo..&gt;</a>\
                  10-Jun-2025 14:22  4.5M
        </pre></body></html>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://example.com/files", 0);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().name()).isEqualTo("a-quite-long-report-file-name-example.pdf");
  }

  @Test
  void preservesALiteralPlusInAFileNameInsteadOfDecodingItToASpace() {
    // #229 review, klein 6: URLDecoder.decode is built for application/x-www-form-urlencoded
    // (query strings), where '+' means a space - but a listing's href is a URL *path* segment,
    // where a literal '+' has no such meaning and can be an ordinary character in a real file
    // name. Before the fix, "bericht+final.pdf" surfaced here as "bericht final.pdf".
    String html =
        """
        <html><head><title>Index of /files/</title></head><body>
        <pre><a href="bericht+final.pdf">bericht+final.pdf</a>\
                  10-Jun-2025 14:22  1K
        </pre></body></html>
        """;

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        service.parseDirectory(html, "https://example.com/files", 0);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().name()).isEqualTo("bericht+final.pdf");
  }

  @Test
  void looksLikeDirectoryListingRecognizesEveryLayout() {
    assertThat(service.looksLikeDirectoryListing("<table><tr><td>a</td></tr></table>")).isTrue();
    assertThat(
            service.looksLikeDirectoryListing(
                "<pre><a href=\"a.txt\">a.txt</a> 01-Jan-2025 00:00 100</pre>"))
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

  // --- #693: isRedirectOriginTrusted's http->https upgrade exception -------------------------

  @Test
  void redirectOriginTrusted_allowsASameHostUpgradeAtBothDefaultPorts() {
    assertThat(
            AutoindexCrawlerService.isRedirectOriginTrusted(
                URI.create("http://host.example/a"), URI.create("https://host.example/a")))
        .isTrue();
  }

  @Test
  void redirectOriginTrusted_allowsASameHostUpgradeAtMatchingExplicitPorts() {
    assertThat(
            AutoindexCrawlerService.isRedirectOriginTrusted(
                URI.create("http://host.example:8443/a"),
                URI.create("https://host.example:8443/a")))
        .isTrue();
  }

  @Test
  void redirectOriginTrusted_allowsAnUpgradeWhereOnlyTheHttpSideNamesTheStandardPortExplicitly() {
    // PR #699 review, finding 1: the original raw getPort() comparison missed this - an explicit
    // ":80" on the http side compared unequal to the https side's unspecified (-1) port, even
    // though both are the standard port for their own scheme.
    assertThat(
            AutoindexCrawlerService.isRedirectOriginTrusted(
                URI.create("http://host.example:80/a"), URI.create("https://host.example/a")))
        .isTrue();
  }

  @Test
  void redirectOriginTrusted_allowsAnUpgradeWhereOnlyTheHttpsSideNamesTheStandardPortExplicitly() {
    // PR #699 review, finding 1: the mirror image - a server that writes the standard https port
    // explicitly into its own Location header.
    assertThat(
            AutoindexCrawlerService.isRedirectOriginTrusted(
                URI.create("http://host.example/a"), URI.create("https://host.example:443/a")))
        .isTrue();
  }

  @Test
  void redirectOriginTrusted_rejectsAnUpgradeWithDifferingExplicitPorts() {
    // #693's Soll-Zustand is deliberately narrow: "Standard-Ports (80->443) bzw. explizit
    // gleicher Port" - an explicit http port that differs from an explicit https port is not
    // covered, even though both individually look plausible.
    assertThat(
            AutoindexCrawlerService.isRedirectOriginTrusted(
                URI.create("http://host.example:8080/a"),
                URI.create("https://host.example:8443/a")))
        .isFalse();
  }

  @Test
  void redirectOriginTrusted_rejectsAnUpgradeToADifferentHost() {
    assertThat(
            AutoindexCrawlerService.isRedirectOriginTrusted(
                URI.create("http://host.example/a"), URI.create("https://angreifer.example/a")))
        .isFalse();
  }

  @Test
  void redirectOriginTrusted_stillRejectsADowngrade() {
    // isSchemeDowngrade is checked independently by every caller and refuses this before
    // isRedirectOriginTrusted is even consulted (see
    // AutoindexCrawlerService#sendFollowingRedirects)
    // - this asserts the trust check itself would not accidentally treat a downgrade as trusted if
    // that ordering were ever changed.
    assertThat(
            AutoindexCrawlerService.isRedirectOriginTrusted(
                URI.create("https://host.example/a"), URI.create("http://host.example/a")))
        .isFalse();
  }

  @Test
  void redirectOriginTrusted_trueForAGenuineSameOriginRedirect() {
    assertThat(
            AutoindexCrawlerService.isRedirectOriginTrusted(
                URI.create("https://host.example/a"), URI.create("https://host.example/b")))
        .isTrue();
  }

  // --- maintainer nachtrag to #693 (21.08.2026): distinguishable, sanitized messages ---------

  @Test
  void redirectRejectionMessage_forForeignHostNeverCarriesPathQueryOrCredentials() {
    // The Location header of a rejected redirect is server-controlled input that can carry a
    // token, session id or other sensitive query parameter - the message must name only the
    // rejected target's scheme and host, never its path, query, fragment or userinfo.
    String message =
        AutoindexCrawlerService.redirectRejectionMessage(
            AutoindexCrawlerService.RedirectRejectionReason.FOREIGN_HOST,
            URI.create("https://user:secret@angreifer.example:8443/pfad?token=geheim#frag"));

    assertThat(message).contains("https://angreifer.example:8443");
    assertThat(message).doesNotContain("secret");
    assertThat(message).doesNotContain("token");
    assertThat(message).doesNotContain("geheim");
    assertThat(message).doesNotContain("pfad");
    assertThat(message).doesNotContain("frag");
  }

  @Test
  void redirectRejectionMessage_distinguishesForeignHostFromProtocolDowngrade() {
    URI target = URI.create("http://host.example/a");
    String foreignHostMessage =
        AutoindexCrawlerService.redirectRejectionMessage(
            AutoindexCrawlerService.RedirectRejectionReason.FOREIGN_HOST, target);
    String downgradeMessage =
        AutoindexCrawlerService.redirectRejectionMessage(
            AutoindexCrawlerService.RedirectRejectionReason.PROTOCOL_DOWNGRADE, target);

    assertThat(foreignHostMessage).isNotEqualTo(downgradeMessage);
    assertThat(foreignHostMessage).contains("fremden Host");
    assertThat(downgradeMessage).contains("Downgrade");
  }
}
