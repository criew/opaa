import { useId, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import SectionHead from './SectionHead'

interface PageSectionProps {
  /** The section's head - monospace, spaced, on a hairline. Names the section for assistive tech. */
  title: string
  /** One sentence under the head, where the section needs an explanation. */
  description?: ReactNode
  /** The section's own action, on the baseline of the head. */
  action?: ReactNode
  /** `h3` where the section sits under another head. */
  headingLevel?: 'h2' | 'h3'
  children: ReactNode
}

/**
 * A section of an administration page: heading on a hairline, below it the
 * content — **no box**.
 *
 * This is the language the account list already speaks and that applies to the whole area
 * here: structure comes from typography, white space and horizontal lines. A frame all around
 * carries no information; three of them nested (section, entry, input field) only make a
 * page slower to read. Frames remain where they carry usability: on input fields,
 * buttons, dialogs and on the dashed empty state.
 *
 * The section is one for screen readers as well: `section` with `aria-labelledby` on the
 * heading — so the outline is not lost, only its border.
 */
export default function PageSection({
  title,
  description,
  action,
  headingLevel = 'h2',
  children,
}: PageSectionProps) {
  // `useId` instead of a module counter: stable across renders and unique even when
  // two sections carry the same title.
  const headId = useId()
  return (
    <Box component="section" aria-labelledby={headId} sx={{ mb: 4 }}>
      {action ? (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 2,
            flexWrap: 'wrap',
            borderBottom: 1,
            borderColor: 'divider',
            pb: 1,
            mb: description ? 1 : 2,
          }}
        >
          <SectionHead id={headId} component={headingLevel} underline={false}>
            {title}
          </SectionHead>
          <Box sx={{ flex: 'none' }}>{action}</Box>
        </Box>
      ) : (
        <Box sx={{ mb: description ? -1 : 0 }}>
          <SectionHead id={headId} component={headingLevel}>
            {title}
          </SectionHead>
        </Box>
      )}
      {description && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2, maxWidth: '80ch' }}>
          {description}
        </Typography>
      )}
      {children}
    </Box>
  )
}
