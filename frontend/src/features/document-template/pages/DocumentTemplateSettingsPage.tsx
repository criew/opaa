import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import DescriptionOutlinedIcon from '@mui/icons-material/DescriptionOutlined'
import { useAuthStore } from '@/shared/session/authStore'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import { contentWidth } from '@/shared/theme/tokens'
import DocumentTemplateSettingsSection from '../components/DocumentTemplateSettingsSection'

/** The installation switch „Vorlagen" in the administration (system admin only). */
export default function DocumentTemplateSettingsPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.form }}>
        <PageHeading title="Vorlagen" gutterBottom />
        <Alert severity="info">
          Vorlagen schaltet die Systemverwaltung ein. Für Ihr Konto ist diese Seite nicht
          freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.list }}>
        <AreaPageHeader
          icon={DescriptionOutlinedIcon}
          title="Vorlagen"
          description="Gilt für die gesamte Installation: ob Word-Vorlagen im Katalog stehen und angelegt werden können. Wer anlegen darf, regelt das Anlegerecht „Vorlagen anlegen“. Änderungen wirken sofort."
        />
        <DocumentTemplateSettingsSection />
      </Box>
    </Box>
  )
}
