import { Link as RouterLink } from 'react-router'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
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
  return (
    <>
      {/* Every tab is a link to the same page with another `?tab=` — middle click and
         „in neuem Tab öffnen" work, and a bookmark lands in the right area. */}
      <Tabs
        value={activeTab}
        aria-label="Bereiche dieser Bibliothek"
        variant="scrollable"
        scrollButtons={false}
        sx={{
          // at the right edge a fade shows that further tabs lie beyond
          '& .MuiTabs-scroller': {
            maskImage: { xs: 'linear-gradient(to right, #000 85%, transparent)', sm: 'none' },
          },
          borderBottom: 1,
          borderColor: 'divider',
          mb: 4,
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
