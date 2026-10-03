package io.opaa.indexing.source.nextcloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reading {@code PROPFIND} answers as Nextcloud 34 sends them. */
class DavMultistatusTest {

  /** A depth-1 answer of Nextcloud 34.0.4, shortened to one folder and one file. */
  private static final String ANSWER =
      "<?xml version=\"1.0\"?>\n<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\""
          + " xmlns:oc=\"http://owncloud.org/ns\" xmlns:nc=\"http://nextcloud.org/ns\">"
          + "<d:response><d:href>/remote.php/dav/files/admin/Projekte/</d:href><d:propstat><d:prop>"
          + "<d:resourcetype><d:collection/></d:resourcetype><d:getetag>&quot;6ac1106f84d19&quot;"
          + "</d:getetag><oc:fileid>76</oc:fileid><nc:mount-type></nc:mount-type></d:prop>"
          + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat><d:prop>"
          + "<d:getcontentlength/><d:getcontenttype/></d:prop><d:status>HTTP/1.1 404 Not Found"
          + "</d:status></d:propstat></d:response>"
          + "<d:response><d:href>/remote.php/dav/files/admin/Projekte/Neu%20%2b%20Alt/b%C3%A4r.txt"
          + "</d:href><d:propstat><d:prop><d:resourcetype/><d:getetag>&quot;456e7ebb27d11ab6efa66d5b1a"
          + "059bf6&quot;</d:getetag><d:getcontentlength>6</d:getcontentlength><d:getcontenttype>"
          + "text/plain</d:getcontenttype><oc:fileid>78</oc:fileid><nc:mount-type>shared"
          + "</nc:mount-type></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
          + "</d:multistatus>";

  @Test
  void readsFoldersAndFilesWithTheirIdentityAndFeature() throws Exception {
    List<DavResource> resources =
        DavMultistatus.parse(new ByteArrayInputStream(ANSWER.getBytes(StandardCharsets.UTF_8)));

    assertThat(resources).hasSize(2);
    DavResource folder = resources.get(0);
    assertThat(folder.collection()).isTrue();
    assertThat(folder.path()).isEqualTo("/remote.php/dav/files/admin/Projekte");
    assertThat(folder.etag()).isEqualTo("6ac1106f84d19");
    assertThat(folder.size()).as("a 404 propstat contributes nothing").isEqualTo(-1);
    DavResource file = resources.get(1);
    assertThat(file.collection()).isFalse();
    assertThat(file.name()).isEqualTo("bär.txt");
    assertThat(file.path()).isEqualTo("/remote.php/dav/files/admin/Projekte/Neu + Alt/bär.txt");
    assertThat(file.size()).isEqualTo(6);
    assertThat(file.contentType()).isEqualTo("text/plain");
    assertThat(file.fileId()).isEqualTo("78");
    assertThat(file.mountType()).isEqualTo("shared");
  }

  @Test
  void readsThePrincipalOfTheSignedInUser() throws Exception {
    String answer =
        "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/remote.php/dav/"
            + "</d:href><d:propstat><d:prop><d:current-user-principal><d:href>/remote.php/dav/"
            + "principals/users/tech-uid/</d:href></d:current-user-principal></d:prop><d:status>"
            + "HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";

    assertThat(
            DavMultistatus.principalHref(
                new ByteArrayInputStream(answer.getBytes(StandardCharsets.UTF_8))))
        .isEqualTo("/remote.php/dav/principals/users/tech-uid/");
  }

  @Test
  void refusesADocumentTypeDeclarationInsteadOfResolvingIt() {
    String answer =
        "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
            + "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>&x;</d:href></d:response>"
            + "</d:multistatus>";

    assertThatThrownBy(
            () ->
                DavMultistatus.parse(
                    new ByteArrayInputStream(answer.getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(DavMultistatus.DavFormatException.class);
  }

  @Test
  void refusesAnAnswerThatIsNoMultistatus() {
    assertThatThrownBy(
            () ->
                DavMultistatus.parse(
                    new ByteArrayInputStream(
                        "<html><body>Login</body></html>".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(DavMultistatus.DavFormatException.class);
  }
}
