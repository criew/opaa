import { Navigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import MailOutlinedIcon from '@mui/icons-material/MailOutlined'
import { useAuthStore } from '../stores/authStore'
import PageHeading from '../components/a11y/PageHeading'
import AreaPageHeader from '../components/AreaPageHeader'
import AreaTabs from '../components/AreaTabs'
import MailServerSection from '../components/admin/mail/MailServerSection'
import MailTemplatesSection from '../components/admin/mail/MailTemplatesSection'
import { contentWidth } from '../theme/tokens'

export type MailSettingsTab = 'server' | 'templates'

function isMailSettingsTab(value: string | undefined): value is MailSettingsTab {
  return value === 'server' || value === 'templates'
}

// „SMTP-Zugang" statt „Server": Das Formular darunter hat selbst ein Feld „Server", und zwei
// Bedienelemente derselben Seite mit demselben Namen sind für Screenreader nicht auseinanderzuhalten.
const tabs: Array<{ value: MailSettingsTab; label: string }> = [
  { value: 'server', label: 'SMTP-Zugang' },
  { value: 'templates', label: 'Vorlagen' },
]

/**
 * Die E-Mail-Einstellungen der Systemverwaltung (#1542, ADR-0033 Entscheidung 10): SMTP-Zugang
 * samt Statusanzeige und Testversand, und der Wortlaut der zwölf Nachrichten.
 *
 * Die beiden Bereiche sind eigene Routen (`/admin/mail/server`, `/admin/mail/templates`) und
 * keine lokale Zustandsvariable: Ein Verweis auf die Vorlagenverwaltung soll dort landen, und ein
 * Neuladen soll den Bereich behalten.
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
