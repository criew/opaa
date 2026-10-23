import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import PrivacyTipOutlinedIcon from '@mui/icons-material/PrivacyTipOutlined'
import { useAuthStore } from '@/shared/session/authStore'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import { contentWidth } from '@/shared/theme/tokens'
import SelfDisclosureSettingsSection from '../components/SelfDisclosureSettingsSection'

/** The installation's data protection settings in the administration (system admin only). */
export default function PrivacySettingsPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Datenschutz" gutterBottom />
        <Alert severity="info">
          Die Einstellungen zum Datenschutz pflegt die Systemverwaltung. Für Ihr Konto ist diese
          Seite nicht freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={PrivacyTipOutlinedIcon}
          title="Datenschutz"
          description="Gilt für die gesamte Installation: wie Personen Auskunft über ihre eigenen Daten erhalten."
        />
        <SelfDisclosureSettingsSection />
      </Box>
    </Box>
  )
}
