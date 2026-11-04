import { useId } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { useBrandingStore } from '../branding/brandingStore'

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
   * Logo emblem alone, no product name - the 64px global rail has no room
   * for text, and its `nav` landmark already names the region for assistive tech.
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
 * a narrow viewport both need. Where no logo is configured, the name alone is the mark - that is
 * what the OPAA standard looks like.
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
  // One mask per instance: the rail and the mobile header can render the mark at the same time.
  const maskId = `opaa-mark-cut-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  const logoUrl = (preferLoginLogo ? branding.loginLogoUrl : undefined) ?? branding.logoUrl

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
        ) : (
          // The OPAA standard mark "Fundstelle": an answer bubble of text lines with a verified badge.
          // Bubble in the surrounding text colour, badge in the accent; lines and the badge ring are
          // cut out by a mask, so the mark reads on navy, white and dark grounds alike.
          <Box
            component="svg"
            viewBox="0 0 64 64"
            aria-hidden="true"
            sx={{ width: logoHeight, height: logoHeight, flexShrink: 0 }}
          >
            <mask id={maskId} maskUnits="userSpaceOnUse" x={0} y={0} width={64} height={64}>
              <rect width={64} height={64} fill="#fff" />
              <path d="M16 20h22M16 30h14" stroke="#000" strokeWidth={5} strokeLinecap="round" />
              <circle cx={47} cy={45} r={15} fill="#000" />
            </mask>
            <path
              mask={`url(#${maskId})`}
              fill="currentColor"
              d="M18 7h24a12 12 0 0 1 12 12v13a12 12 0 0 1-12 12H23l-10 10v-10.6A12 12 0 0 1 6 32V19A12 12 0 0 1 18 7z"
            />
            <Box
              component="circle"
              cx={47}
              cy={45}
              r={13}
              sx={{ fill: (t) => t.palette.primary.main }}
            />
            <path
              d="M41 45.5l4.2 4.2 7.8-8.4"
              fill="none"
              stroke="#fff"
              strokeWidth={4}
              strokeLinecap="round"
              strokeLinejoin="round"
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
          sx={{
            color: 'text.secondary',
            ...(vertical ? { display: 'block', fontSize: 12.5, mt: 0.5 } : {}),
          }}
        >
          {branding.claim}
        </Typography>
      )}
    </Box>
  )
}
