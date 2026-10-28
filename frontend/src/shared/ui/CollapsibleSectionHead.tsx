import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Typography from '@mui/material/Typography'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import { useSectionCollapsed, useUiStore } from '@/shared/stores/uiStore'

interface CollapsibleSectionHeadProps {
  /** Key under which the folded state is remembered (uiStore). */
  sectionKey: string
  label: string
  /** Shown after the label as visible text, e.g. the number of entries of the whole group. */
  count?: number
  /** The id of the region the head shows and hides. */
  controls: string
  component?: 'h2' | 'h3'
  /** Trailing actions in the same row, outside the toggle. */
  actions?: ReactNode
  id?: string
}

/**
 * A group head of the space column that folds its group (disclosure pattern, guidelines 5.13):
 * the heading contains one button with `aria-expanded`, the chevron turns with the state, and the
 * actions in the same row stay reachable while the group is folded. The count is part of the
 * button's text, so its accessible name is exactly what is shown ("Angeheftet 8").
 */
export default function CollapsibleSectionHead({
  sectionKey,
  label,
  count,
  controls,
  component = 'h3',
  actions,
  id,
}: CollapsibleSectionHeadProps) {
  const collapsed = useSectionCollapsed(sectionKey)
  const toggle = useUiStore((s) => s.toggleSidebarSection)
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', minHeight: 28, gap: 0.25 }}>
      <Typography component={component} id={id} sx={{ flexGrow: 1, minWidth: 0, m: 0 }}>
        <ButtonBase
          aria-expanded={!collapsed}
          aria-controls={controls}
          onClick={() => toggle(sectionKey)}
          sx={{
            width: '100%',
            justifyContent: 'flex-start',
            gap: 0.5,
            px: 0.5,
            py: 0.5,
            borderRadius: '6px',
            color: 'text.secondary',
            '&:hover': { bgcolor: 'action.hover' },
            '&.Mui-focusVisible': { outlineOffset: '-2px' },
          }}
        >
          <ChevronRightIcon
            aria-hidden
            sx={{
              fontSize: 16,
              flexShrink: 0,
              transform: collapsed ? 'none' : 'rotate(90deg)',
              transition: 'transform 120ms',
              '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
            }}
          />
          {/* Label and count share one inline box, so the space between them is rendered and
              the accessible name reads "Angeheftet 8", matching the visible text. */}
          <Typography component="span" variant="overline" noWrap sx={{ lineHeight: 1.6 }}>
            {label}
            {count !== undefined && ` ${count}`}
          </Typography>
        </ButtonBase>
      </Typography>
      {actions && (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.25, flexShrink: 0 }}>
          {actions}
        </Box>
      )}
    </Box>
  )
}
