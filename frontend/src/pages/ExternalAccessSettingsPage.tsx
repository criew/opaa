import { Navigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import HubOutlinedIcon from '@mui/icons-material/HubOutlined'
import { useAuthStore } from '../stores/authStore'
import PageHeading from '../components/a11y/PageHeading'
import AreaPageHeader from '../components/AreaPageHeader'
import AreaTabs from '../components/AreaTabs'
import ExternalAccessChannelSection from '../components/admin/externalaccess/ExternalAccessChannelSection'
import ExternalAccessTokenAdminSection from '../components/admin/externalaccess/ExternalAccessTokenAdminSection'
import { contentWidth } from '../theme/tokens'

export type ExternalAccessTab = 'channel' | 'tokens'

const tabs: Array<{ value: ExternalAccessTab; label: string }> = [
  { value: 'channel', label: 'Kanaleinstellungen' },
  { value: 'tokens', label: 'Zugangstokens' },
]

function isExternalAccessTab(value: string | undefined): value is ExternalAccessTab {
  return value === 'channel' || value === 'tokens'
}

/**
 * Die Fremdzugänge in der Systemverwaltung: die Kanaleinstellungen (#1717) und die Bestandsliste
 * aller Zugangstokens samt Sperren (#1719).
 *
 * Die beiden Bereiche sind eigene Routen, damit ein Verweis auf die Tokenliste dort landet und ein
 * Neuladen den Bereich behält. Ein Tippfehler im Pfad wird nicht stillschweigend als
 * „Kanaleinstellungen“ gelesen; die fehlende Angabe dagegen schon, denn `/admin/external-access`
 * ist der Eintrag der Bereichsnavigation.
 */
export default function ExternalAccessSettingsPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const { tab } = useParams()

  if (tab !== undefined && !isExternalAccessTab(tab)) {
    return <Navigate to="/admin/external-access" replace />
  }
  const activeTab: ExternalAccessTab = isExternalAccessTab(tab) ? tab : 'channel'

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Fremdzugänge" gutterBottom />
        <Alert severity="info">
          Die Fremdzugänge werden von der Systemverwaltung gepflegt. Für Ihr Konto ist diese Seite
          nicht freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={HubOutlinedIcon}
          title="Fremdzugänge"
          description="Gilt für die gesamte Installation: Ob externe KI-Werkzeuge über Zugangstokens auf freigegebene Wissensbibliotheken zugreifen dürfen, aus welchen Netzbereichen und in welchem Umfang. Voreinstellung ist „aus“."
        />

        <AreaTabs
          tabs={tabs}
          value={activeTab}
          href={(value) => `/admin/external-access/${value}`}
          label="Bereiche der Fremdzugänge"
          idPrefix="external-access"
        >
          {(value) =>
            value === 'channel' ? (
              <ExternalAccessChannelSection />
            ) : (
              <ExternalAccessTokenAdminSection />
            )
          }
        </AreaTabs>
      </Box>
    </Box>
  )
}
