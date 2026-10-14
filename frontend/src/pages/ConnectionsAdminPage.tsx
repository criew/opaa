import { Navigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import CableOutlinedIcon from '@mui/icons-material/CableOutlined'
import { useAuthStore } from '@/shared/session/authStore'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import AreaTabs from '@/shared/ui/AreaTabs'
import ConnectionProfilesTab from '../components/admin/connections/ConnectionProfilesTab'
import ConnectorTypesTab from '../components/admin/connections/ConnectorTypesTab'
import ConnectionSettingsTab from '../components/admin/connections/ConnectionSettingsTab'
import ExternalToolsTab from '../components/admin/externalaccess/ExternalToolsTab'
import {
  CONNECTIONS_ADMIN_TABS,
  connectionsAdminRoute,
  type ConnectionsAdminTab,
} from '@/shared/routes'
import { contentWidth } from '@/shared/theme/tokens'

const TAB_LABELS: Record<ConnectionsAdminTab, string> = {
  access: 'Zugänge',
  'source-types': 'Quellarten',
  'external-tools': 'Externe KI-Werkzeuge',
  settings: 'Einstellungen',
}

const tabs = CONNECTIONS_ADMIN_TABS.map((value) => ({ value, label: TAB_LABELS[value] }))

function isTab(value: string | undefined): value is ConnectionsAdminTab {
  return (CONNECTIONS_ADMIN_TABS as readonly string[]).includes(value ?? '')
}

function TabContent({ tab }: { tab: ConnectionsAdminTab }) {
  switch (tab) {
    case 'access':
      return <ConnectionProfilesTab />
    case 'source-types':
      return <ConnectorTypesTab />
    case 'external-tools':
      return <ExternalToolsTab />
    case 'settings':
      return <ConnectionSettingsTab />
    default: {
      const unknown: never = tab
      throw new Error(`Unknown connections tab ${String(unknown)}`)
    }
  }
}

/**
 * Die Anbindungen: wie OPAA mit anderen Systemen verbunden ist, in beide Richtungen.
 * Zugänge und Quellarten bestimmen, woraus OPAA liest; externe KI-Werkzeuge lesen aus OPAA. Die
 * Reiter sind Routen; ein unbekannter Reiter führt auf den ersten, statt still einen anderen zu
 * zeigen.
 */
export default function ConnectionsAdminPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const { tab } = useParams()

  if (!isTab(tab)) return <Navigate to={connectionsAdminRoute('access')} replace />

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Anbindungen" gutterBottom />
        <Alert severity="info">
          Die Anbindungen werden von der Systemverwaltung gepflegt. Für Ihr Konto ist diese Seite
          nicht freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={CableOutlinedIcon}
          title="Anbindungen"
          description="Wie OPAA mit anderen Systemen verbunden ist, in beide Richtungen: Über Zugänge liest OPAA Inhalte aus anderen Systemen, externe KI-Werkzeuge lesen Fundstellen aus OPAA."
        />
        <AreaTabs
          tabs={tabs}
          value={tab}
          href={connectionsAdminRoute}
          label="Bereiche der Anbindungen"
          idPrefix="connections"
        >
          {(value) => <TabContent tab={value} />}
        </AreaTabs>
      </Box>
    </Box>
  )
}
