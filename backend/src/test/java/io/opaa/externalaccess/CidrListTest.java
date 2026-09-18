package io.opaa.externalaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The syntax check and the normalisation the stored CIDR list of the channel relies on (#1717). */
class CidrListTest {

  @Test
  void acceptsIpv4AndIpv6RangesAndSingleAddresses() {
    assertThat(CidrList.isValid("10.0.0.0/8")).isTrue();
    assertThat(CidrList.isValid("192.168.178.24")).isTrue();
    assertThat(CidrList.isValid("fc00::/7")).isTrue();
    assertThat(CidrList.isValid("::1/128")).isTrue();
    assertThat(CidrList.isValid("2001:db8::1")).isTrue();
  }

  @Test
  void refusesHostNamesAndMalformedRanges() {
    assertThat(CidrList.isValid("stadt.example")).isFalse();
    assertThat(CidrList.isValid("localhost")).isFalse();
    assertThat(CidrList.isValid("10.0.0.0/33")).isFalse();
    assertThat(CidrList.isValid("10.0.0.0/")).isFalse();
    assertThat(CidrList.isValid("10.0.0.256/24")).isFalse();
    assertThat(CidrList.isValid("10.0.0.0/acht")).isFalse();
    assertThat(CidrList.isValid("")).isFalse();
    assertThat(CidrList.isValid(null)).isFalse();
  }

  @Test
  void normalizesToTrimmedLowercaseDistinctEntriesInOrder() {
    assertThat(
            CidrList.normalize(Arrays.asList(" 10.0.0.0/8 ", "FC00::/7", "10.0.0.0/8", "", null)))
        .containsExactly("10.0.0.0/8", "fc00::/7");
    assertThat(CidrList.normalize(null)).isEmpty();
  }

  @Test
  void anEmptyColumnIsAnEmptyListAndSurvivesARoundTrip() {
    CidrListConverter converter = new CidrListConverter();

    assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    assertThat(converter.convertToEntityAttribute("")).isEmpty();
    List<String> cidrs = List.of("10.0.0.0/8", "::1/128");
    assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(cidrs)))
        .isEqualTo(cidrs);
  }
}
