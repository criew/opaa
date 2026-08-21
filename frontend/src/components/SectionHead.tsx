import Typography from '@mui/material/Typography'

interface SectionHeadProps {
  children: string
  component?: 'h2' | 'h3'
  /** Set false when the surrounding row carries the hairline (e.g. a head with a trailing action). */
  underline?: boolean
}

/**
 * Eyebrow-style section head on a hairline, shared by the migrated management pages
 * (guidelines 3.4/5.3): monospace uppercase over the content block it introduces.
 */
export default function SectionHead({
  children,
  component = 'h2',
  underline = true,
}: SectionHeadProps) {
  return (
    <Typography
      component={component}
      sx={{
        fontFamily: 'monospace',
        fontSize: 10,
        fontWeight: 500,
        letterSpacing: '0.08em',
        textTransform: 'uppercase',
        color: 'text.secondary',
        ...(underline ? { borderBottom: 1, borderColor: 'divider', pb: 1, mb: 2 } : { mb: 0 }),
      }}
    >
      {children}
    </Typography>
  )
}
