import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { useBrandingStore } from '../stores/brandingStore'

interface BrandMarkProps {
  /** Rendered height of the logo in pixels; the width follows the image's own aspect ratio. */
  logoHeight?: number
  /** Whether to render the claim under the product name. */
  showClaim?: boolean
  /** Typography variant of the product name - the sidebar and the sign-in page differ here. */
  variant?: 'h6' | 'h5'
  /** 'vertical' stacks logo, name and claim centered - the sign-in card's block (mockup 1f). */
  orientation?: 'horizontal' | 'vertical'
  /**
   * Logo emblem alone, no product name - the 64px global rail (#786, mockup 2a) has no room
   * for text, and its `nav` landmark already names the region for assistive tech.
   */
  logoOnly?: boolean
}

/**
 * The operator's mark: their logo where one is configured, their product name, and optionally their
 * claim (#583, guidelines 7 - "Name, Claim und Logo werden nie hart eingebettet"). One component
 * rather than three copies, because the sidebar, the mobile header and the sign-in page all show
 * the same thing at different sizes, and three copies is how they would drift apart.
 *
 * Renders the product name as text next to the logo rather than relying on the logo to carry it:
 * an operator's logo is frequently a wordless emblem, and the name is what a screen reader user and
 * a narrow viewport both need. Where no logo is configured, the name alone is the mark - that is
 * what the OPAA standard looks like.
 */
export default function BrandMark({
  logoHeight = 28,
  showClaim = false,
  variant = 'h6',
  orientation = 'horizontal',
  logoOnly = false,
}: BrandMarkProps) {
  const branding = useBrandingStore((s) => s.branding)
  const vertical = orientation === 'vertical'

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
        {branding.logoUrl ? (
          <Box
            component="img"
            src={branding.logoUrl}
            // The name is right next to it, so the image adds nothing for a screen reader and is
            // marked decorative instead of read out twice (WCAG 1.1.1).
            alt=""
            height={logoHeight}
            sx={{ height: logoHeight, width: 'auto', maxWidth: 160, objectFit: 'contain' }}
          />
        ) : (
          // The OPAA standard mark from mockup 1a: accent-stroked squircle, document lines in
          // the surrounding text color so it works on navy and on white alike (#658 Nachbesserung).
          <Box
            component="svg"
            viewBox="0 0 24 24"
            aria-hidden="true"
            sx={{ width: logoHeight - 2, height: logoHeight - 2, flexShrink: 0 }}
          >
            <Box
              component="rect"
              x={2.5}
              y={2.5}
              width={19}
              height={19}
              rx={5.5}
              sx={{ fill: 'none', stroke: (t) => t.palette.primary.main, strokeWidth: 1.8 }}
            />
            <Box
              component="path"
              d="M7 13h6M7 16.5h8.5"
              sx={{
                fill: 'none',
                stroke: 'currentColor',
                strokeWidth: 1.8,
                strokeLinecap: 'round',
              }}
            />
            <Box
              component="rect"
              x={14.5}
              y={6.5}
              width={3.5}
              height={3.5}
              rx={1}
              sx={{ fill: (t) => t.palette.primary.main }}
            />
          </Box>
        )}
        {!logoOnly && (
          <Typography
            variant={variant}
            component="span"
            sx={{ fontWeight: 700, ...(vertical && { fontSize: 24 }) }}
          >
            {branding.productName}
          </Typography>
        )}
      </Box>
      {!logoOnly && showClaim && branding.claim && (
        <Typography
          variant="caption"
          color="text.secondary"
          sx={vertical ? { display: 'block', fontSize: 12.5, mt: 0.5 } : undefined}
        >
          {branding.claim}
        </Typography>
      )}
    </Box>
  )
}
