import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import ImportExportOutlinedIcon from '@mui/icons-material/ImportExportOutlined'
import { useAuthStore } from '@/shared/session/authStore'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import { contentWidth } from '@/shared/theme/tokens'
import SettingsExportSection from '../components/SettingsExportSection'
import SettingsImportSection from '../components/SettingsImportSection'

/** Export and import of the installation's settings in the administration (system admin only). */
export default function SettingsTransferPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Einstellungen übertragen" gutterBottom />
        <Alert severity="info">
          Die Einstellungen überträgt die Systemverwaltung. Für Ihr Konto ist diese Seite nicht
          freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={ImportExportOutlinedIcon}
          title="Einstellungen übertragen"
          description="Gilt für die gesamte Installation: alle Einstellungen der Verwaltung als Datei sichern und in eine andere Installation übernehmen."
        />
        <SettingsExportSection />
        <SettingsImportSection />
      </Box>
    </Box>
  )
}
