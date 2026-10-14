import { Navigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import MailOutlinedIcon from '@mui/icons-material/MailOutlined'
import { useAuthStore } from '@/shared/session/authStore'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import AreaTabs from '@/shared/ui/AreaTabs'
import MailServerSection from '../components/MailServerSection'
import MailTemplatesSection from '../components/MailTemplatesSection'
import { contentWidth } from '@/shared/theme/tokens'

export type MailSettingsTab = 'server' | 'templates'

function isMailSettingsTab(value: string | undefined): value is MailSettingsTab {
  return value === 'server' || value === 'templates'
}

// „SMTP-Zugang" instead of „Server": the form below has a field „Server" itself, and two
// controls on the same page with the same name cannot be told apart by screen readers.
const tabs: Array<{ value: MailSettingsTab; label: string }> = [
  { value: 'server', label: 'SMTP-Zugang' },
  { value: 'templates', label: 'Vorlagen' },
]

/**
 * The system administration's mail settings (ADR-0033 Entscheidung 10): SMTP access
 * including status display and test sending, and the wording of the twelve messages.
 *
 * The two areas are routes of their own (`/admin/mail/server`, `/admin/mail/templates`) and
 * no local state variable: a link to the template management should land there, and a
 * reload should keep the area.
 */
export default function MailSettingsPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const { tab } = useParams()

  // A typo in the path is not silently reinterpreted as the server tab: the address bar ends up
  // saying what is actually shown.
  if (!isMailSettingsTab(tab)) {
    return <Navigate to="/admin/mail/server" replace />
  }
  const activeTab: MailSettingsTab = tab

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="E-Mail" gutterBottom />
        <Alert severity="info">
          Die E-Mail-Einstellungen werden von der Systemverwaltung gepflegt. Für Ihr Konto ist diese
          Seite nicht freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={MailOutlinedIcon}
          title="E-Mail"
          description="Gilt für die gesamte Anwendung. Einladungen, Rücksetzlinks und Hinweise an Konten gehen über diesen Zugang; Änderungen wirken ohne Neustart."
        />

        <AreaTabs
          tabs={tabs}
          value={activeTab}
          href={(value) => `/admin/mail/${value}`}
          label="Bereiche der E-Mail-Einstellungen"
          idPrefix="mail"
        >
          {(value) => (value === 'server' ? <MailServerSection /> : <MailTemplatesSection />)}
        </AreaTabs>
      </Box>
    </Box>
  )
}
