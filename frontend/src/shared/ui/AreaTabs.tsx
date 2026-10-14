import type { ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import { radius } from '../theme/tokens'

export interface AreaTab<T extends string> {
  value: T
  label: string
  /** A neutral count after the label, e.g. open entries; part of the tab's accessible name. */
  count?: number
}

interface AreaTabsProps<T extends string> {
  /** The areas in order; the first one is where a bare area path lands. */
  tabs: ReadonlyArray<AreaTab<T>>
  /** The area currently shown - the route decides it, not a click. */
  value: T
  /** The route of an area, so a tab is a link: middle-click and „open in new tab" work. */
  href: (value: T) => string
  /** Names the tab list for assistive tech, e.g. „Bereiche der Benutzerverwaltung". */
  label: string
  /** Prefix of the generated ids; unique per page. */
  idPrefix: string
  /** The content of one area. Called only for the active one. */
  children: (value: T) => ReactNode
}

/**
 * The tabs of an area page — bar **and** panels.
 *
 * The areas are routes of their own, not states: a link should land in the right area,
 * and a reload should keep it. That is why every tab is a link and not a click handler.
 *
 * Every tab gets its panel, so that every `aria-controls` points to an existing element.
 * Only the active one's children are rendered, though — otherwise the invisible area's
 * requests would keep running in the background.
 */
export default function AreaTabs<T extends string>({
  tabs,
  value,
  href,
  label,
  idPrefix,
  children,
}: AreaTabsProps<T>) {
  return (
    <>
      <Tabs
        value={value}
        aria-label={label}
        // Long labels or a narrow screen scroll instead of being cut off.
        variant="scrollable"
        scrollButtons="auto"
        allowScrollButtonsMobile
        sx={{
          borderBottom: 1,
          borderColor: 'divider',
          mb: 3,
          minHeight: 42,
          '& .MuiTab-root': {
            textTransform: 'none',
            fontSize: 13.5,
            fontWeight: 500,
            minHeight: 42,
            px: 2,
          },
        }}
      >
        {tabs.map((entry) => (
          <Tab
            key={entry.value}
            label={
              entry.count === undefined ? (
                entry.label
              ) : (
                <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 1 }}>
                  {entry.label}{' '}
                  <Box
                    component="span"
                    sx={{
                      minWidth: 22,
                      px: 0.75,
                      borderRadius: `${radius.pill}px`,
                      fontSize: 12,
                      fontWeight: 600,
                      lineHeight: '20px',
                      textAlign: 'center',
                      bgcolor: entry.count > 0 ? 'action.selected' : 'transparent',
                      color: entry.count > 0 ? 'text.primary' : 'text.secondary',
                    }}
                  >
                    {entry.count}
                  </Box>
                </Box>
              )
            }
            value={entry.value}
            component={RouterLink}
            to={href(entry.value)}
            id={`${idPrefix}-tab-${entry.value}`}
            aria-controls={`${idPrefix}-tabpanel-${entry.value}`}
          />
        ))}
      </Tabs>
      {tabs.map((entry) => (
        <Box
          key={entry.value}
          role="tabpanel"
          id={`${idPrefix}-tabpanel-${entry.value}`}
          aria-labelledby={`${idPrefix}-tab-${entry.value}`}
          hidden={entry.value !== value}
        >
          {entry.value === value && children(entry.value)}
        </Box>
      ))}
    </>
  )
}
