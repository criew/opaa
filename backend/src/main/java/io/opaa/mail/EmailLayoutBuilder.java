package io.opaa.mail;

import org.springframework.stereotype.Component;

/**
 * Wraps a rendered content fragment in the branded HTML frame every OPAA mail shares (#1536,
 * ADR-0033 Entscheidung 10): product name and accent colour from {@code BrandingSettings}, no logo
 * in this phase.
 *
 * <p>Built for mail clients, not browsers: one centred table with a fixed maximum width, inline
 * styles only, a declared light colour scheme, and a hidden preheader.
 *
 * <p><b>Preconditions.</b> {@code contentHtml} arrives already rendered and HTML-escaped; {@code
 * accentColor} must match {@code ^#[0-9A-Fa-f]{6}$} - {@code BrandingSettingsService} enforces that
 * on write, and this class interpolates the value raw into a {@code style} attribute. Every other
 * value it places into the frame is escaped here.
 */
@Component
public class EmailLayoutBuilder {

  private static final String INK = "#18191f";
  private static final String INK_SOFT = "#3c3f49";
  private static final String MUTED = "#767a85";
  private static final String PAPER = "#f4f5f7";
  private static final String HAIRLINE = "#e3e5ea";
  private static final String BODY_FONT =
      "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
  private static final String MONO_FONT =
      "'SFMono-Regular',ui-monospace,Menlo,Consolas,'Liberation Mono',monospace";

  static final String FOOTER_LINE =
      "Diese Nachricht wurde automatisch erzeugt. Bitte antworten Sie nicht darauf.";

  /**
   * @param brandName the product name shown as the wordmark
   * @param accentColor the operator's accent colour as a {@code #rrggbb} triplet
   * @param preheader the hidden inbox-preview line
   * @param contentHtml the inner fragment, already rendered and escaped
   * @param ctaUrl the call-to-action target; blank means no button and no fallback link
   * @param ctaText the button label
   */
  public String wrap(
      String brandName,
      String accentColor,
      String preheader,
      String contentHtml,
      String ctaUrl,
      String ctaText) {
    String accent = accentColor == null || accentColor.isBlank() ? INK : accentColor;
    String callToAction =
        ctaUrl == null || ctaUrl.isBlank() ? "" : callToActionBlock(accent, ctaUrl, ctaText);
    return """
        <!DOCTYPE html>
        <html lang="de">
          <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <meta name="color-scheme" content="light only" />
            <meta name="supported-color-schemes" content="light" />
          </head>
          <body style="margin:0;padding:0;background:%s;-webkit-font-smoothing:antialiased;">
            <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:%s;font-size:1px;line-height:1px;">%s</div>
            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background:%s;">
              <tr>
                <td align="center" style="padding:40px 16px;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0"
                         style="max-width:560px;width:100%%;background:#ffffff;border:1px solid %s;
                                border-radius:14px;overflow:hidden;font-family:%s;">
                    <tr><td style="height:4px;background:%s;font-size:0;line-height:0;">&nbsp;</td></tr>
                    <tr>
                      <td style="padding:28px 36px 0;">
                        <span style="font-size:19px;font-weight:700;letter-spacing:-0.02em;color:%s;">%s</span>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:20px 36px 8px;color:%s;font-size:15px;line-height:1.65;">
                        %s
                        %s
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:8px 36px 30px;">
                        <div style="border-top:1px solid %s;padding-top:18px;color:%s;font-size:12px;line-height:1.6;">%s</div>
                      </td>
                    </tr>
                  </table>
                </td>
              </tr>
            </table>
          </body>
        </html>
        """
        .formatted(
            PAPER,
            PAPER,
            escape(preheader),
            PAPER,
            HAIRLINE,
            BODY_FONT,
            accent,
            INK,
            escape(brandName),
            INK_SOFT,
            contentHtml,
            callToAction,
            HAIRLINE,
            MUTED,
            escape(FOOTER_LINE));
  }

  /**
   * The button plus the same address in plain text underneath: a client that strips the button, or
   * a reader who distrusts one, must still be able to reach the link.
   */
  private String callToActionBlock(String accent, String ctaUrl, String ctaText) {
    String label = ctaText == null || ctaText.isBlank() ? "Öffnen" : ctaText;
    String url = escape(ctaUrl);
    return """
        <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:26px 0 6px;">
          <tr>
            <td style="border-radius:10px;background:%s;">
              <a href="%s" style="display:inline-block;padding:13px 26px;color:#ffffff;text-decoration:none;font-weight:600;font-size:15px;">%s</a>
            </td>
          </tr>
        </table>
        <p style="margin:16px 0 0;color:%s;font-size:12px;line-height:1.6;">Falls die Schaltfläche nicht funktioniert, kopieren Sie diese Adresse in Ihren Browser:</p>
        <p style="margin:6px 0 0;">
          <a href="%s" style="color:%s;font-size:12px;word-break:break-all;font-family:%s;text-decoration:none;">%s</a>
        </p>
        """
        .formatted(accent, url, escape(label), MUTED, url, accent, MONO_FONT, url);
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
