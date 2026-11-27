import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { OPAA_BRANDING, useBrandingStore } from '../branding/brandingStore'
import { fontSize } from '../theme/tokens'
import { MARK, MARK_ACCENT, WORDMARK } from './brandMarkPaths'

/** Widest the standard wordmark may be in the 64px rail, leaving the tile its side padding. */
const RAIL_WORDMARK_WIDTH = 44

/** One outline in the text colour, one in the brand's Hellblau (the accented a, the underline). */
function Outline({
  shape,
  height,
  label,
}: {
  shape: { x: number; y: number; width: number; height: number; base: string; accent: string }
  height: number
  label?: string
}) {
  return (
    <Box
      component="svg"
      viewBox={`${shape.x} ${shape.y} ${shape.width} ${shape.height}`}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      sx={{ height, width: (height * shape.width) / shape.height, flexShrink: 0, display: 'block' }}
    >
      {shape.base && <path fill="currentColor" d={shape.base} />}
      <path fill={MARK_ACCENT} d={shape.accent} />
    </Box>
  )
}

interface BrandMarkProps {
  /** Rendered height of the logo in pixels; the width follows the image's own aspect ratio. */
  logoHeight?: number
  /** Whether to render the claim under the product name. */
  showClaim?: boolean
  /** Typography variant of the product name - sidebar, sign-in card and brand panel differ here. */
  variant?: 'h6' | 'h5' | 'h4'
  /** 'vertical' stacks logo, name and claim centered - the sign-in card's block. */
  orientation?: 'horizontal' | 'vertical'
  /**
   * Logo alone, no product name - the 64px global rail has no room for text, and its `nav`
   * landmark already names the region for assistive tech. The standard wordmark is drawn
   * decorative there, narrowed to the rail's width.
   */
  logoOnly?: boolean
  /**
   * Prefers the sign-in page's own logo where one is configured, falling back to the app
   * logo and then to the OPAA standard mark. Only the screens before a session set this.
   */
  preferLoginLogo?: boolean
}

/**
 * The operator's mark: their logo where one is configured, their product name, and optionally their
 * claim (guidelines 7 - "Name, Claim und Logo werden nie hart eingebettet"). One component
 * rather than three copies, because the sidebar, the mobile header and the sign-in page all show
 * the same thing at different sizes, and three copies is how they would drift apart.
 *
 * Renders the product name as text next to the logo rather than relying on the logo to carry it:
 * an operator's logo is frequently a wordless emblem, and the name is what a screen reader user and
 * a narrow viewport both need. Where no logo is configured, the OPAA standard mark (the underlined
 * "o") stands next to the name; with the standard name as well, the OPAA wordmark replaces both and
 * carries the name as its accessible label.
 */
export default function BrandMark({
  logoHeight = 28,
  showClaim = false,
  variant = 'h6',
  orientation = 'horizontal',
  logoOnly = false,
  preferLoginLogo = false,
}: BrandMarkProps) {
  const branding = useBrandingStore((s) => s.branding)
  const vertical = orientation === 'vertical'
  const logoUrl = (preferLoginLogo ? branding.loginLogoUrl : undefined) ?? branding.logoUrl
  const standardWordmark = !logoUrl && branding.productName === OPAA_BRANDING.productName

  return (
    <Box sx={vertical ? { textAlign: 'center' } : undefined}>
      <Box
        sx={{
          display: 'flex',
          flexDirection: vertical ? 'column' : 'row',
          alignItems: 'center',
          gap: vertical ? 1.5 : 1,
        }}
      >
        {logoUrl ? (
          <Box
            component="img"
            src={logoUrl}
            // The name is right next to it, so the image adds nothing for a screen reader and is
            // marked decorative instead of read out twice (WCAG 1.1.1).
            alt=""
            height={logoHeight}
            sx={{
              height: logoHeight,
              width: 'auto',
              // Width follows the height instead of a fixed cap: a wide wordmark logo on
              // the login page would otherwise be clipped to sidebar width.
              maxWidth: logoOnly ? 40 : Math.max(160, logoHeight * 4),
              objectFit: 'contain',
            }}
          />
        ) : standardWordmark && logoOnly ? (
          // The rail's nav landmark already names the region, so the wordmark stays decorative.
          <Outline
            shape={WORDMARK}
            height={Math.min(logoHeight, (RAIL_WORDMARK_WIDTH * WORDMARK.height) / WORDMARK.width)}
          />
        ) : standardWordmark ? (
          <Outline shape={WORDMARK} height={logoHeight} label={branding.productName} />
        ) : (
          <Outline shape={MARK} height={logoHeight} />
        )}
        {!logoOnly && !standardWordmark && (
          <Typography
            variant={variant}
            component="span"
            sx={{ fontWeight: 700, ...(vertical && { fontSize: fontSize.wizardTitle }) }}
          >
            {branding.productName}
          </Typography>
        )}
      </Box>
      {!logoOnly && showClaim && branding.claim && (
        <Typography
          variant="caption"
          sx={{
            color: 'text.secondary',
            ...(vertical ? { display: 'block', fontSize: fontSize.meta, mt: 0.5 } : {}),
          }}
        >
          {branding.claim}
        </Typography>
      )}
    </Box>
  )
}
