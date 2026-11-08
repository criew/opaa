package io.opaa.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FatalErrorsTest {

  @Test
  void aMissingClassOfTheShippedLibrariesIsFatal() {
    assertThat(FatalErrors.isFatal(new NoClassDefFoundError("org/apache/tika/Tika"))).isTrue();
    assertThat(FatalErrors.isFatal(new NoClassDefFoundError())).isTrue();
    assertThat(FatalErrors.isFatal(new UnsatisfiedLinkError("com/github/luben/zstd/Zstd")))
        .isTrue();
  }

  // regression guard for #3619: the codec zstd-jni is not shipped, one zstd entry needs it
  @Test
  void theMissingClassOfTheUnshippedZstdCodecIsCausedByTheOneInput() {
    assertThat(
            FatalErrors.isFatal(new NoClassDefFoundError("com/github/luben/zstd/ZstdInputStream")))
        .isFalse();
    assertThat(FatalErrors.isFatal(new NoClassDefFoundError("com.github.luben.zstd.Zstd")))
        .isFalse();
  }

  @Test
  void anOutOfMemoryErrorStaysFatalAndAStackOverflowDoesNot() {
    assertThat(FatalErrors.isFatal(new OutOfMemoryError())).isTrue();
    assertThat(FatalErrors.isFatal(new StackOverflowError())).isFalse();
  }
}
