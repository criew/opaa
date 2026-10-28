import { useCallback, useEffect, useRef, useState } from 'react'
import { Navigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import AssignmentLateOutlinedIcon from '@mui/icons-material/AssignmentLateOutlined'
import { useAuthStore } from '@/shared/session/authStore'
import { getSuccessionCounts } from '../api/successionApi'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import AreaTabs from '@/shared/ui/AreaTabs'
import SuccessionList from '../components/SuccessionList'
import SuccessionSettingsSection from '../components/SuccessionSettingsSection'
import { SUCCESSION_TABS, type SuccessionTab } from '../components/successionPresentation'
import { contentWidth } from '@/shared/theme/tokens'

const TAB_ORDER: SuccessionTab[] = ['open', 'grants', 'groups']

function isSuccessionTab(value: string | undefined): value is SuccessionTab {
  return value === 'open' || value === 'grants' || value === 'groups'
}

/**
 * How many entries each tab holds - per kind of entry only, never per person (ADR-0036,
 * Entscheidung 6). Undefined while loading or when the counts could not be read. One request for
 * all tabs, reloaded on every tab change; an answer that arrives after a newer request is dropped.
 */
function useTabCounts(
  activeTab: SuccessionTab,
): [Partial<Record<SuccessionTab, number>>, () => void] {
  const [counts, setCounts] = useState<Partial<Record<SuccessionTab, number>>>({})
  const latest = useRef(0)
  const load = useCallback(() => {
    const request = ++latest.current
    void getSuccessionCounts()
      .then((figures): Partial<Record<SuccessionTab, number>> => ({
        open: figures.openSuccession,
        grants: figures.grantsWithoutRecipient,
        groups: figures.groupWithoutEffect,
      }))
      .catch(() => ({}))
      .then((next) => {
        if (request === latest.current) setCounts(next)
      })
  }, [])
  useEffect(() => {
    load()
  }, [load, activeTab])
  return [counts, load]
}

/** The page body for the system administration - the counts are only asked for here. */
function SuccessionContent({ activeTab }: { activeTab: SuccessionTab }) {
  const [counts, reloadCounts] = useTabCounts(activeTab)
  return (
    <AreaTabs
      tabs={TAB_ORDER.map((value) => ({
        value,
        label: SUCCESSION_TABS[value].title,
        count: counts[value],
      }))}
      value={activeTab}
      href={(value) => `/admin/succession/${value}`}
      label="Bereiche der Liste"
      idPrefix="succession"
    >
      {(value) => <SuccessionList text={SUCCESSION_TABS[value]} onChanged={reloadCounts} />}
    </AreaTabs>
  )
}

/**
 * What nobody takes care of any more (ADR-0036, Entscheidung 6): objects and groups whose
 * responsible person left or whose group emptied. The list shows, it does not push - no deadline,
 * no reminder, no e-mail.
 */
export default function SuccessionPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const { tab } = useParams()
  // A new threshold marks the entries anew: the list is read again under a fresh key.
  const [listRevision, setListRevision] = useState(0)

  if (tab !== undefined && !isSuccessionTab(tab)) {
    return <Navigate to="/admin/succession/open" replace />
  }
  const activeTab: SuccessionTab = isSuccessionTab(tab) ? tab : 'open'

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Ohne Zuständigkeit" gutterBottom />
        <Alert severity="info">
          Diese Liste führt die Systemverwaltung. Für Ihr Konto ist diese Seite nicht freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={AssignmentLateOutlinedIcon}
          title="Ohne Zuständigkeit"
          description="Was niemand mehr verantworten kann, etwa nach dem Ausscheiden einer Person. Die Inhalte bleiben nutzbar; neue Freigaben sind gesperrt, bis wieder jemand zuständig ist."
        />

        <SuccessionContent key={listRevision} activeTab={activeTab} />

        <SuccessionSettingsSection onSaved={() => setListRevision((current) => current + 1)} />
      </Box>
    </Box>
  )
}
