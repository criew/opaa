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
}: BrandMarkProps) {
  const branding = useBrandingStore((s) => s.branding)

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        {branding.logoUrl && (
          <Box
            component="img"
            src={branding.logoUrl}
            // The name is right next to it, so the image adds nothing for a screen reader and is
            // marked decorative instead of read out twice (WCAG 1.1.1).
            alt=""
            height={logoHeight}
            sx={{ height: logoHeight, width: 'auto', maxWidth: 160, objectFit: 'contain' }}
          />
        )}
        <Typography variant={variant} component="span" sx={{ fontWeight: 700 }}>
          {branding.productName}
        </Typography>
      </Box>
      {showClaim && branding.claim && (
        <Typography variant="caption" color="text.secondary">
          {branding.claim}
        </Typography>
      )}
    </Box>
  )
}
