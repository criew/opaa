import type { ElementType, ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import PageHeading from './a11y/PageHeading'
import { formatAreaCount, type AreaCountValue } from './areaCount'
import { attachActionSlot, attachCountSlot } from './areaHeaderSlots'
import { AREA_PAGE_HEADER_MARK } from './sectionGap'
import { controlHeight, fontSize, iconSize, readingWidth } from '../theme/tokens'

interface AreaPageHeaderProps {
  /** The page's single <h1>. */
  title: string
  /** Overrides the document title when it should be more specific than the heading. */
  documentTitle?: string
  /**
   * The sentence under the title: what this page governs and for whom, in at most two lines.
   * Every page of an area carries one - a title alone leaves the reader to guess the scope.
   */
  description: ReactNode
  /**
   * The count of the page's list next to the title: „40 Konten", filtered „12 von 40 Konten". A
   * list further down the page, or in a tab, sets it with `AreaPageCount` instead.
   */
  count?: AreaCountValue
  /** A short fact next to the title that is no count. */
  meta?: ReactNode
  /**
   * The page's one primary action, pushed to the trailing edge of the title row. A
   * `CreateButton` further down the page, or in a tab, lands in the same place.
   */
  action?: ReactNode
  /**
   * The area's own mark, left of title and description - an outline icon from
   * `@mui/icons-material`. Decorative: the title already says the same thing.
   */
  icon: ElementType
}

/** A slot stays out of the row's layout until `AreaPageCount` or `CreateButton` fills it. */
const EMPTY_SLOT_SX = { '&:empty': { display: 'none' } } as const

/**
 * The header every area page begins with (guidelines 4.6): title, next to it the count of the
 * page's list, at the right edge the one primary action, below it the sentence that names the
 * scope. Count and action are also slots: `AreaPageCount` and `CreateButton` rendered anywhere
 * below - in the content of a tab - appear here, so both follow the selected tab.
 *
 * The spacing is here and only here: the title row without padding of its own, below it a
 * fixed gap to the description and from it to the content.
 */
export default function AreaPageHeader({
  title,
  documentTitle,
  description,
  count,
  meta,
  action,
  icon: Icon,
}: AreaPageHeaderProps) {
  return (
    <Box sx={{ mb: 3 }} {...AREA_PAGE_HEADER_MARK}>
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
          minHeight: controlHeight.regular,
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
            <Icon sx={{ fontSize: { xs: iconSize.lg, sm: iconSize.lg } }} />
          </Box>
          <PageHeading title={title} documentTitle={documentTitle} />
        </Box>
        {count && (
          <Typography component="span" sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
            {formatAreaCount(count)}
          </Typography>
        )}
        <Box component="span" ref={attachCountSlot} sx={EMPTY_SLOT_SX} />
        {meta && (
          <Typography component="span" sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
            {meta}
          </Typography>
        )}
        {/* Of the two, whichever is present takes the auto margin that pushes it to the trailing
           edge; a page writes its action in one of the two ways. */}
        {action && <Box sx={{ ml: 'auto', flex: 'none', alignSelf: 'center' }}>{action}</Box>}
        <Box
          ref={attachActionSlot}
          sx={{ ml: 'auto', flex: 'none', alignSelf: 'center', ...EMPTY_SLOT_SX }}
        />
      </Box>
      <Typography
        sx={{ fontSize: fontSize.meta, color: 'text.secondary', mt: 0.5, maxWidth: readingWidth }}
      >
        {description}
      </Typography>
    </Box>
  )
}
