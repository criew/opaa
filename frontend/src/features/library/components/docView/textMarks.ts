import type { SxProps, Theme } from '@mui/material/styles'
import { BRAND, deskColor, type DocViewVariant } from './docViewVariant'

type Variant = Exclude<DocViewVariant, null>

/** The CSS Custom Highlight of the passage per variant: band (A), marker underline (B), tint (C). */
export function labHighlightStyles(variant: Variant) {
  if (variant === 'b')
    return {
      backgroundColor: 'rgba(0, 157, 219, 0.12)',
      textDecorationLine: 'underline',
      textDecorationColor: '#0055A4',
      textDecorationThickness: '2px',
      textUnderlineOffset: '0.25em',
    }
  return {
    backgroundColor: variant === 'a' ? 'rgba(0, 157, 219, 0.24)' : 'rgba(0, 157, 219, 0.14)',
  }
}

/** Flags the blocks the passage runs through, so A draws its margin bar and C its frame. */
export function markPassageBlocks(content: HTMLElement, range: Range) {
  content.querySelectorAll('[data-passage]').forEach((el) => el.removeAttribute('data-passage'))
  const blocks = 'p, li, pre, td, th, blockquote, h1, h2, h3, h4, h5, h6'
  const start = range.startContainer.parentElement?.closest(blocks)
  const end = range.endContainer.parentElement?.closest(blocks)
  for (const block of [start, end])
    if (block && content.contains(block)) block.setAttribute('data-passage', '')
}

export function labDeskSx(variant: Variant): SxProps<Theme> {
  return (theme) => ({
    bgcolor:
      variant === 'b' ? 'background.paper' : deskColor(variant, theme.palette.mode === 'dark'),
    px: { xs: 0, sm: 3 },
    py: { xs: 0, sm: 3 },
  })
}

/** The blocks a passage runs through: margin bar (A), nothing (B), labelled frame (C). */
export function labPassageBlockSx(variant: Variant): Record<string, unknown> {
  return variant === 'a'
    ? {
        position: 'relative',
        '&::before': {
          content: '""',
          position: 'absolute',
          left: -14,
          top: 2,
          bottom: 2,
          width: 4,
          borderRadius: 2,
          bgcolor: BRAND.blue,
        },
      }
    : variant === 'c'
      ? {
          position: 'relative',
          outline: `2px solid ${BRAND.blue}`,
          outlineOffset: 6,
          mt: 4,
          borderRadius: '4px',
          '&::before': {
            content: '"Fundstelle"',
            position: 'absolute',
            left: -8,
            bottom: 'calc(100% + 8px)',
            bgcolor: BRAND.blue,
            color: '#fff',
            font: '600 11px/16px Quicksand, Inter, sans-serif',
            px: 1,
            py: '1px',
            borderRadius: '4px',
          },
        }
      : {}
}

export function labPaperSx(variant: Variant): SxProps<Theme> {
  const bar = labPassageBlockSx(variant)
  return {
    ...(variant === 'b'
      ? { px: { xs: 2, sm: 3 }, py: 2 }
      : {
          maxWidth: 820,
          mx: 'auto',
          width: '100%',
          bgcolor: 'background.paper',
          color: 'text.primary',
          borderRadius: { xs: 0, sm: '4px' },
          boxShadow: {
            xs: 'none',
            sm: '0 1px 2px rgba(16,24,40,0.08), 0 10px 28px rgba(36,40,85,0.14)',
          },
          px: { xs: 2, sm: 7 },
          py: { xs: 2, sm: 6 },
        }),
    '& [data-passage]': bar,
  } as SxProps<Theme>
}

/** The cited passage of a Textfassung, per variant. */
export function labTargetSx(variant: Variant): Record<string, unknown> {
  if (variant === 'a')
    return {
      bgcolor: 'rgba(0, 157, 219, 0.16)',
      borderLeft: `4px solid ${BRAND.blue}`,
      pl: 1.5,
      py: 0.75,
      borderRadius: '2px 6px 6px 2px',
    }
  if (variant === 'b')
    return {
      bgcolor: 'rgba(0, 157, 219, 0.12)',
      textDecorationLine: 'underline',
      textDecorationColor: BRAND.blue,
      textDecorationThickness: '2px',
      textUnderlineOffset: '0.25em',
    }
  return {
    position: 'relative',
    outline: `2px solid ${BRAND.blue}`,
    outlineOffset: 6,
    mt: 4,
    bgcolor: 'rgba(0, 157, 219, 0.08)',
    borderRadius: '4px',
    '&::before': {
      content: '"Fundstelle"',
      position: 'absolute',
      left: -8,
      bottom: 'calc(100% + 8px)',
      bgcolor: BRAND.blue,
      color: '#fff',
      font: '600 11px/16px Quicksand, Inter, sans-serif',
      px: 1,
      py: '1px',
      borderRadius: '4px',
    },
  }
}
