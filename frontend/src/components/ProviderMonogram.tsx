import Box from '@mui/material/Box'
import { alpha } from '@mui/material/styles'
import { fontFamily, radius } from '../theme/tokens'

/** The one or two letters a provider is recognised by: "Verzeichnisdienst" → V, "Landes Portal" → LP. */
function providerInitials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return '?'
  if (words.length === 1) return words[0].charAt(0).toUpperCase()
  return (words[0].charAt(0) + words[1].charAt(0)).toUpperCase()
}

interface ProviderMonogramProps {
  name: string
  size?: number
  /**
   * 'accent': tinted tile on a light surface (the resting state); 'inverse': on an accent surface
   * (the primary sign-in tile); 'muted': a disabled provider.
   */
  tone?: 'accent' | 'inverse' | 'muted'
}

/**
 * Monogram tile of an identity provider - the same mark on the sign-in page and in the provider
 * management, so what an administrator configures is what a user recognises. Accent-derived like
 * GlobalBadge (10 % surface, 40 % border), so a branding override recolours it with the rest of
 * the app. Decorative: the provider's name always stands right next to it.
 */
export default function ProviderMonogram({
  name,
  size = 40,
  tone = 'accent',
}: ProviderMonogramProps) {
  return (
    <Box
      component="span"
      aria-hidden="true"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        flex: 'none',
        width: size,
        height: size,
        borderRadius: `${size >= 40 ? radius.md : radius.sm}px`,
        fontFamily: fontFamily.mono,
        fontSize: Math.round(size * 0.4),
        fontWeight: 600,
        letterSpacing: '0.02em',
        lineHeight: 1,
        border: 1,
        ...(tone === 'inverse' && {
          color: 'inherit',
          bgcolor: alpha('#FFFFFF', 0.16),
          borderColor: alpha('#FFFFFF', 0.4),
        }),
        ...(tone === 'accent' && {
          color: 'primary.main',
          bgcolor: (t) => alpha(t.palette.primary.main, 0.1),
          borderColor: (t) => alpha(t.palette.primary.main, 0.4),
        }),
        ...(tone === 'muted' && {
          color: 'text.disabled',
          bgcolor: (t) => alpha(t.palette.text.primary, 0.04),
          borderColor: 'divider',
        }),
      }}
    >
      {providerInitials(name)}
    </Box>
  )
}
