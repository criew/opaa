import { Navigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import AssignmentLateOutlinedIcon from '@mui/icons-material/AssignmentLateOutlined'
import type { SuccessionKind } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import PageHeading from '../components/a11y/PageHeading'
import AreaPageHeader from '../components/AreaPageHeader'
import AreaTabs from '../components/AreaTabs'
import SuccessionList from '../components/succession/SuccessionList'
import { contentWidth } from '../theme/tokens'

export type SuccessionTab = 'open' | 'grants' | 'groups'

const tabs: Array<{ value: SuccessionTab; label: string }> = [
  { value: 'open', label: 'Offene Nachfolgen' },
  { value: 'grants', label: 'Freigaben ohne Empfänger' },
  { value: 'groups', label: 'Gruppen ohne Wirkung' },
]

const kindOf: Record<SuccessionTab, SuccessionKind> = {
  open: 'OPEN_SUCCESSION',
  grants: 'GRANTS_WITHOUT_RECIPIENT',
  groups: 'GROUP_WITHOUT_EFFECT',
}

const emptyTextOf: Record<SuccessionTab, string> = {
  open: 'Für jedes Objekt gibt es eine handlungsfähige zuständige Stelle.',
  grants: 'Jede Gruppe, die Rechte trägt, hat mindestens ein aktives Mitglied.',
  groups: 'Es gibt keine interne Gruppe ohne Wirkung und ohne aktives Mitglied.',
}

function isSuccessionTab(value: string | undefined): value is SuccessionTab {
  return value === 'open' || value === 'grants' || value === 'groups'
}

/**
 * Die Betriebsliste des Lebenszyklus (#1821 gegen #1819, ADR-0036 Entscheidung 6): drei Reiter mit
 * derselben Mechanik — Objekt, Adressat, Alter, Sichtungsvermerk. Die Liste zeigt, sie treibt
 * nicht: keine Frist, keine Erinnerung, keine E-Mail.
 */
export default function SuccessionPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const { tab } = useParams()

  if (tab !== undefined && !isSuccessionTab(tab)) {
    return <Navigate to="/admin/succession/open" replace />
  }
  const activeTab: SuccessionTab = isSuccessionTab(tab) ? tab : 'open'

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Lebenszyklus" gutterBottom />
        <Alert severity="info">
          Die Betriebsliste führt die Systemverwaltung. Für Ihr Konto ist diese Seite nicht
          freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={AssignmentLateOutlinedIcon}
          title="Lebenszyklus"
          description="Objekte und Gruppen, für die niemand mehr handeln kann. Die Liste ist vollständig ab dem ersten Tag und geht vom Objekt aus. Das jeweilige Objekt bleibt nutzbar, bestehende Rechte bleiben, nichts wird gelöscht — eingefroren ist allein die Reichweite."
        />

        <AreaTabs
          tabs={tabs}
          value={activeTab}
          href={(value) => `/admin/succession/${value}`}
          label="Reiter der Betriebsliste"
          idPrefix="succession"
        >
          {(value) => <SuccessionList kind={kindOf[value]} emptyText={emptyTextOf[value]} />}
        </AreaTabs>
      </Box>
    </Box>
  )
}
