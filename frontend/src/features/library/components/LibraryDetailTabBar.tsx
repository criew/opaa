import { useEffect } from 'react'
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
  // the chosen tab stays in sight when the bar gets narrower than its tabs; the bar scrolls to a
  // tab's start, so the first visible tab is whole and never half under the arrow
  useEffect(() => {
    const reveal = () => {
      const active = document.getElementById(`library-tab-${activeTab}`)
      const scroller = active?.closest<HTMLElement>('.MuiTabs-scroller')
      if (!active || !scroller) return
      const tabs = [...scroller.querySelectorAll<HTMLElement>('[role="tab"]')]
      const end = active.offsetLeft + active.offsetWidth
      const start = tabs.find((tab) => end - tab.offsetLeft <= scroller.clientWidth) ?? active
      scroller.scrollLeft = start.offsetLeft
    }
    // the bar animates its own scroll to the selected tab whenever its tabs are measured anew
    // (first layout, web fonts, resize); for a short while after each, a scroll that ends is
    // brought to a tab's edge - a scroll by the user later stays as it is
    const scroller = document
      .getElementById(`library-tab-${activeTab}`)
      ?.closest<HTMLElement>('.MuiTabs-scroller')
    let settle = 0
    const arm = () => {
      reveal()
      scroller?.addEventListener('scrollend', reveal)
      window.clearTimeout(settle)
      settle = window.setTimeout(() => scroller?.removeEventListener('scrollend', reveal), 1500)
    }
    const frame = requestAnimationFrame(() => requestAnimationFrame(arm))
    const list = scroller?.querySelector('[role="tablist"]')
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => arm())
    if (list) observer?.observe(list)
    window.addEventListener('resize', arm)
    return () => {
      cancelAnimationFrame(frame)
      window.clearTimeout(settle)
      observer?.disconnect()
      scroller?.removeEventListener('scrollend', reveal)
      window.removeEventListener('resize', arm)
    }
  }, [activeTab])
  return (
    <>
      {/* Every tab is a link to the same page with another `?tab=` — middle click and
         „in neuem Tab öffnen" work, and a bookmark lands in the right area. */}
      <Tabs
        value={activeTab}
        aria-label="Bereiche dieser Bibliothek"
        variant="scrollable"
        // below 600 px arrows show that further tabs lie beyond, and lead there
        scrollButtons="auto"
        allowScrollButtonsMobile
        sx={{
          '& .MuiTabs-scrollButtons.Mui-disabled': { opacity: 0.3 },
          '& .MuiTabs-scrollButtons': { width: 28 },
          // room after the last tab, so the bar can start at any tab's edge below 600 px
          '& .MuiTabs-list::after': {
            content: '""',
            flex: 'none',
            width: { xs: 120, sm: 0 },
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
