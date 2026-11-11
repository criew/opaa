import Typography from '@mui/material/Typography'
import { fontSize } from '../theme/tokens'
import { SECTION_GAP_SELECTOR } from './sectionGap'

interface SectionHeadProps {
  children: string
  component?: 'h2' | 'h3'
  /** Set false when the surrounding row carries the hairline (e.g. a head with a trailing action). */
  underline?: boolean
  /** Lets a surrounding `section` name itself after this head via `aria-labelledby`. */
  id?: string
}

/**
 * The one section head of the app (guidelines 4.6): monospace uppercase on a hairline over the
 * content block it introduces. The gap to whatever stands before it is part of the head, so a
 * second section never sticks to the content of the first.
 */
export default function SectionHead({
  children,
  component = 'h2',
  underline = true,
  id,
}: SectionHeadProps) {
  return (
    <Typography
      id={id}
      component={component}
      sx={{
        fontFamily: 'monospace',
        fontSize: fontSize.label,
        fontWeight: 500,
        letterSpacing: '0.08em',
        textTransform: 'uppercase',
        color: 'text.secondary',
        ...(underline
          ? {
              borderBottom: 1,
              borderColor: 'divider',
              pb: 1,
              mb: 2,
              [SECTION_GAP_SELECTOR]: { mt: 4 },
            }
          : { mb: 0 }),
      }}
    >
      {children}
    </Typography>
  )
}
