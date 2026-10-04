package io.opaa.library.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.ConnectionProfileRef;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.library.LibraryProfileState;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The profile of a library: its managers read what it sets for the library - address, sign-in,
 * defaults, proxy, TLS switch - every other reader its name only.
 */
class LibraryProfileRefMapperTest {

  private static final UUID ID = UUID.randomUUID();

  @Test
  void aManagerReadsTheFrameOfTheProfile() {
    ConnectionProfileRef ref =
        LibraryResponseMapper.toProfileRef(
            new LibraryProfileState(
                ID,
                "Zugang",
                false,
                new LibraryProfileState.Frame(
                    "https://probe.example.org",
                    ConnectionAuthMethod.PERSONAL_SECRET,
                    ConnectorData.of(Map.of("edition", "DC")),
                    "proxy.example.org:3128",
                    true)));

    assertThat(ref.getId()).isEqualTo(ID);
    assertThat(ref.getName()).isEqualTo("Zugang");
    assertThat(ref.getServerUrl()).isEqualTo("https://probe.example.org");
    assertThat(ref.getAuthMethod()).isEqualTo(ConnectionAuthMethod.PERSONAL_SECRET);
    assertThat(ref.getConnectorDefaults()).isEqualTo(Map.of("edition", "DC"));
    assertThat(ref.getSourceProxy()).isEqualTo("proxy.example.org:3128");
    assertThat(ref.getSourceInsecureSsl()).isTrue();
  }

  @Test
  void anyOtherReaderReadsItsNameOnly() {
    ConnectionProfileRef ref =
        LibraryResponseMapper.toProfileRef(new LibraryProfileState(ID, "Zugang", false, null));

    assertThat(ref.getName()).isEqualTo("Zugang");
    assertThat(ref.getServerUrl()).isNull();
    assertThat(ref.getAuthMethod()).isNull();
    assertThat(ref.getConnectorDefaults()).isNull();
    assertThat(ref.getSourceProxy()).isNull();
    assertThat(ref.getSourceInsecureSsl()).isNull();
  }
}
