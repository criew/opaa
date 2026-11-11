import { useState } from 'react'
import { Navigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import AssignmentLateOutlinedIcon from '@mui/icons-material/AssignmentLateOutlined'
import { useAuthStore } from '@/shared/session/authStore'
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
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.form }}>
        <PageHeading title="Ohne Zuständigkeit" gutterBottom />
        <Alert severity="info">
          Diese Liste führt die Systemverwaltung. Für Ihr Konto ist diese Seite nicht freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.list }}>
        <AreaPageHeader
          icon={AssignmentLateOutlinedIcon}
          title="Ohne Zuständigkeit"
          description="Was niemand mehr verantworten kann, etwa nach dem Ausscheiden einer Person. Die Inhalte bleiben nutzbar; neue Freigaben sind gesperrt, bis wieder jemand zuständig ist."
        />

        <AreaTabs
          key={listRevision}
          tabs={TAB_ORDER.map((value) => ({ value, label: SUCCESSION_TABS[value].title }))}
          value={activeTab}
          href={(value) => `/admin/succession/${value}`}
          label="Bereiche der Liste"
          idPrefix="succession"
        >
          {(value) => <SuccessionList text={SUCCESSION_TABS[value]} />}
        </AreaTabs>

        <SuccessionSettingsSection onSaved={() => setListRevision((current) => current + 1)} />
      </Box>
    </Box>
  )
}
