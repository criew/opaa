package io.opaa.branding;

import io.opaa.api.types.ColorScheme;

/**
 * The OPAA standard every unconfigured branding field falls back to. Kept in one place, and
 * resolved per field rather than per row: an operator who only replaces the logo keeps the standard
 * product name, and a later change to the standard reaches every deployment that never overrode it.
 *
 * <p>The values mirror docs/design/guidelines.md - {@link #PRIMARY_COLOR} is the accent {@code
 * blue.500} the token layer already carries ({@code frontend/src/shared/theme/tokens.ts}). Changing
 * one here without changing it there would split the standard in two.
 */
public final class BrandingDefaults {

  /** Maximum accepted length of {@link EffectiveBranding#productName()}, in characters. */
  public static final int MAX_PRODUCT_NAME_LENGTH = 60;

  /** Maximum accepted length of {@link EffectiveBranding#claim()}, in characters. */
  public static final int MAX_CLAIM_LENGTH = 120;

  /** Maximum accepted length of {@link EffectiveBranding#aiNotice()}, in characters. */
  public static final int MAX_AI_NOTICE_LENGTH = 500;

  public static final String PRODUCT_NAME = "OPAA";

  public static final String CLAIM = "Fragen. Belegen. Entscheiden.";

  /**
   * The notice that tells users the answers come from an AI (Art. 50 KI-VO). It can be replaced but
   * never switched off: a blank value falls back to this text.
   */
  public static final String AI_NOTICE =
      "Antworten werden von einer KI erzeugt und können Fehler enthalten. "
          + "Prüfen Sie wichtige Angaben anhand der Belege.";

  public static final String PRIMARY_COLOR = "#1292EE";

  public static final ColorScheme COLOR_SCHEME = ColorScheme.SYSTEM;

  private BrandingDefaults() {}
}
