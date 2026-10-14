import type { ElementType, ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import PageHeading from './a11y/PageHeading'

interface AreaPageHeaderProps {
  /** The page's single <h1>. */
  title: string
  /** Overrides the document title when it should be more specific than the heading. */
  documentTitle?: string
  /**
   * The sentence under the title: what this page governs and for whom. Every page of an area
   * carries one - a title alone leaves the reader to guess the scope.
   */
  description: ReactNode
  /** A short fact next to the title, typically a count („7 Konten"). */
  meta?: ReactNode
  /** The page's one primary action, pushed to the trailing edge of the title row. */
  action?: ReactNode
  /**
   * The area's own mark, left of title and description - an outline icon from
   * `@mui/icons-material`. Decorative: the title already says the same thing.
   */
  icon: ElementType
}

/**
 * The header of an area page: title, next to it a short detail and the one primary
 * action, below it the sentence that names the scope.
 *
 * A building block of its own, because the same arrangement used to be written four different
 * ways on seven pages — with `gutterBottom` or without, with a flex row or without, the
 * description sometimes as `GlobalScopeNote` (12.5 px) and sometimes as `body2` (14 px). This
 * showed as titles that jump a few pixels when switching between two menu items.
 *
 * The spacing is here and only here: the title row without padding of its own, below it a
 * fixed gap to the description and from it to the content. The negative top margin with which
 * `GlobalScopeNote` used to pull itself under a `gutterBottom` is thus gone.
 */
export default function AreaPageHeader({
  title,
  documentTitle,
  description,
  meta,
  action,
  icon: Icon,
}: AreaPageHeaderProps) {
  return (
    <Box sx={{ mb: 3 }}>
      {/* A flex box, not a Stack: MUI's Stack sets its spacing as `margin-left` on the
         siblings and thereby overrides the `ml: auto` that moves the action to the right
         edge. The minimum height is that of a button - without it the
         description would sit four pixels lower on pages with an action than on the others, exactly
         the jump this building block is meant to remove. */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'baseline',
          gap: 2,
          rowGap: 1,
          flexWrap: 'wrap',
          minHeight: 36.5,
        }}
      >
        {/* Icon and title form a group with its own, narrower spacing and thus stay together
           even when wrapping. The description is deliberately outside: it starts at
           the page's left edge, not below the title - so one edge runs through instead of
           leaving a hole below the icon. */}
        <Box
          sx={{ display: 'flex', alignItems: 'baseline', gap: { xs: 1.25, sm: 1.5 }, minWidth: 0 }}
        >
          {/* The height is the title's **text line**, not that of the title row: the row
             carries the minimum height of a button, the text sits at its top, and an icon
             centred on it would sit eight pixels too low. `aria-hidden`, because the title
             next to it says the same. */}
          <Box
            aria-hidden
            sx={(theme) => ({
              flex: 'none',
              alignSelf: 'flex-start',
              display: 'flex',
              alignItems: 'center',
              height:
                Number(theme.typography.h5.fontSize) * Number(theme.typography.h5.lineHeight ?? 1),
              color: 'primary.main',
            })}
          >
            {/* About one and a half times the title's cap height: enough to work as the area's
               icon, little enough not to outvoice the title. */}
            <Icon sx={{ fontSize: { xs: 21, sm: 24 } }} />
          </Box>
          <PageHeading title={title} documentTitle={documentTitle} />
        </Box>
        {meta && (
          <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
            {meta}
          </Typography>
        )}
        {action && <Box sx={{ ml: 'auto', flex: 'none', alignSelf: 'center' }}>{action}</Box>}
      </Box>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5, maxWidth: '80ch' }}>
        {description}
      </Typography>
    </Box>
  )
}
