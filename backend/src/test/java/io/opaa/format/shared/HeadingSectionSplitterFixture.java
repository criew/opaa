package io.opaa.format.shared;

/** The cut at the default target size, for tests that build a format without Spring. */
public final class HeadingSectionSplitterFixture {

  public static final HeadingSectionSplitter DEFAULT =
      new HeadingSectionSplitter(HeadingSectionSplitter.DEFAULT_SOFT_CHUNK_CHAR_LIMIT);

  private HeadingSectionSplitterFixture() {}
}
