import { Link as RouterLink } from 'react-router'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import useMediaQuery from '@mui/material/useMediaQuery'
import type { Theme } from '@mui/material/styles'
import { TAB_CONTENT_WIDTH } from '@/shared/ui/TabSection'
import type { LibraryDetailTab } from './libraryDetailAccess'

interface LibraryDetailTabBarProps {
  activeTab: LibraryDetailTab
  tabs: Array<{ value: LibraryDetailTab; label: string }>
  /** The search of the page with `tab` active, other URL state kept. */
  tabSearch: (tab: LibraryDetailTab) => string
}

/** The areas of the library detail page as tabs. */
export default function LibraryDetailTabBar({
  activeTab,
  tabs,
  tabSearch,
}: LibraryDetailTabBarProps) {
  const compact = useMediaQuery((theme: Theme) => theme.breakpoints.down('sm'))
  return (
    <>
      {/* Every tab is a link to the same page with another `?tab=` — middle click and
         „in neuem Tab öffnen" work, and a bookmark lands in the right area. */}
      <Tabs
        value={activeTab}
        aria-label="Bereiche dieser Bibliothek"
        // below 600 px the tabs wrap onto a second line instead of scrolling: every tab stays
        // whole and in view, the active one marked by its own underline
        variant={compact ? 'standard' : 'scrollable'}
        scrollButtons={false}
        sx={{
          '& .MuiTabs-list': { flexWrap: { xs: 'wrap', sm: 'nowrap' } },
          '& .MuiTabs-indicator': { display: { xs: 'none', sm: 'block' } },
          '& .MuiTab-root.Mui-selected': {
            boxShadow: { xs: 'inset 0 -2px 0 currentColor', sm: 'none' },
          },
          borderBottom: 1,
          borderColor: 'divider',
          maxWidth: TAB_CONTENT_WIDTH,
          mb: 4,
          minHeight: 42,
          '& .MuiTab-root': {
            textTransform: 'none',
            fontSize: 13.5,
            fontWeight: 500,
            minHeight: 42,
            minWidth: { xs: 0, sm: 90 },
            px: { xs: 1, sm: 2 },
          },
        }}
      >
        {tabs.map((entry) => (
          <Tab
            key={entry.value}
            label={entry.label}
            value={entry.value}
            component={RouterLink}
            to={{ search: tabSearch(entry.value) }}
            // The area is a state of the same page, not a stop of its own: whoever looks through
            // four tabs should leave the page with one „Zurück", not with four.
            replace
            id={`library-tab-${entry.value}`}
            aria-controls={`library-tabpanel-${entry.value}`}
          />
        ))}
      </Tabs>
    </>
  )
}
