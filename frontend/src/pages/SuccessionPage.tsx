import { useCallback, useEffect, useState } from 'react'
import { Navigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import AssignmentLateOutlinedIcon from '@mui/icons-material/AssignmentLateOutlined'
import { useAuthStore } from '../stores/authStore'
import { getSuccessionEntries } from '../services/successionApi'
import PageHeading from '../components/a11y/PageHeading'
import AreaPageHeader from '../components/AreaPageHeader'
import AreaTabs from '../components/AreaTabs'
import SuccessionList from '../components/succession/SuccessionList'
import {
  SUCCESSION_TABS,
  type SuccessionTab,
} from '../components/succession/successionPresentation'
import { contentWidth } from '../theme/tokens'

const TAB_ORDER: SuccessionTab[] = ['open', 'grants', 'groups']

function isSuccessionTab(value: string | undefined): value is SuccessionTab {
  return value === 'open' || value === 'grants' || value === 'groups'
}

/**
 * How many entries each tab holds - per kind of entry only, never per person (ADR-0036,
 * Entscheidung 6). Undefined while loading or when a count could not be read.
 */
function useTabCounts(): [Partial<Record<SuccessionTab, number>>, () => void] {
  const [counts, setCounts] = useState<Partial<Record<SuccessionTab, number>>>({})
  const load = useCallback(() => {
    void Promise.all(
      TAB_ORDER.map((tab) =>
        getSuccessionEntries(SUCCESSION_TABS[tab].kind, 0, 1)
          .then((listing) => [tab, listing.totalElements] as const)
          .catch(() => [tab, undefined] as const),
      ),
    ).then((entries) => setCounts(Object.fromEntries(entries)))
  }, [])
  useEffect(() => {
    load()
  }, [load])
  return [counts, load]
}

/**
 * What nobody takes care of any more (ADR-0036, Entscheidung 6): objects and groups whose
 * responsible person left or whose group emptied. The list shows, it does not push - no deadline,
 * no reminder, no e-mail.
 */
export default function SuccessionPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const { tab } = useParams()
  const [counts, reloadCounts] = useTabCounts()

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
          description="Hier steht, um was sich niemand mehr kümmern kann – etwa weil die verantwortliche Person ausgeschieden ist oder eine Gruppe keine Mitglieder mehr hat. Die Inhalte bleiben nutzbar, und nichts wird gelöscht; neue Freigaben sind aber gesperrt, bis wieder jemand zuständig ist. Die Liste erinnert nicht und verschickt nichts."
        />

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
      </Box>
    </Box>
  )
}
