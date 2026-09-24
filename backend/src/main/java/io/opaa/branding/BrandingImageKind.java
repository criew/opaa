package io.opaa.branding;

/**
 * The three images an operator may store in the branding settings (#582, #1910). One enum rather
 * than three parallel sets of methods: every one of them is validated, stored, served and audited
 * the same way, and three copies of that is exactly how one of them would end up with a weaker
 * rule.
 *
 * <p>Only the ceilings differ, and only for the background: a logo is chrome a few hundred pixels
 * wide, while a background image covers half a screen and is usually a photograph.
 *
 * @param label German name of the image in validation messages and in the audit record
 * @param maxSizeBytes largest accepted upload; also the ceiling the database's own {@code
 *     chk_branding_settings_*_size} constraints carry
 * @param maxEdgePixels largest accepted edge, read from the image header before anything decodes a
 *     pixel - the decompression-bomb guard, not a layout rule
 */
public enum BrandingImageKind {
  LOGO("Das Logo", 512 * 1024, 2000),
  LOGIN_LOGO("Das Logo der Anmeldeseite", 512 * 1024, 2000),
  LOGIN_BACKGROUND("Das Hintergrundbild der Anmeldeseite", 2 * 1024 * 1024, 4000);

  private final String label;
  private final int maxSizeBytes;
  private final int maxEdgePixels;

  BrandingImageKind(String label, int maxSizeBytes, int maxEdgePixels) {
    this.label = label;
    this.maxSizeBytes = maxSizeBytes;
    this.maxEdgePixels = maxEdgePixels;
  }

  public String label() {
    return label;
  }

  public int maxSizeBytes() {
    return maxSizeBytes;
  }

  public int maxEdgePixels() {
    return maxEdgePixels;
  }
}
