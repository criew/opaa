import { Navigate, Link as RouterLink, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import { useAuthStore } from '../stores/authStore'
import PageHeading from '../components/a11y/PageHeading'
import GlobalScopeNote from '../components/GlobalScopeNote'
import MailServerSection from '../components/admin/mail/MailServerSection'
import MailTemplatesSection from '../components/admin/mail/MailTemplatesSection'

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
      <Box sx={{ flexGrow: 1, p: 4, maxWidth: 720 }}>
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
      <Box sx={{ maxWidth: 1040 }}>
        <PageHeading title="E-Mail" />
        <GlobalScopeNote>
          Gilt für die gesamte Anwendung. Einladungen, Rücksetzlinks und Hinweise an Konten gehen
          über diesen Zugang; Änderungen wirken ohne Neustart.
        </GlobalScopeNote>

        <Tabs
          value={activeTab}
          aria-label="Bereiche der E-Mail-Einstellungen"
          sx={{
            borderBottom: 1,
            borderColor: 'divider',
            mb: 3,
            minHeight: 42,
            '& .MuiTab-root': {
              textTransform: 'none',
              fontSize: 13.5,
              fontWeight: 500,
              minHeight: 42,
              px: 2,
            },
          }}
        >
          {tabs.map((entry) => (
            <Tab
              key={entry.value}
              label={entry.label}
              value={entry.value}
              component={RouterLink}
              to={`/admin/mail/${entry.value}`}
              id={`mail-tab-${entry.value}`}
              aria-controls={`mail-tabpanel-${entry.value}`}
            />
          ))}
        </Tabs>

        {/* Both panels exist so that every tab's aria-controls points at a real element. The
            inactive one is hidden and renders no children, so no request of the other area keeps
            running in the background. */}
        <Box
          role="tabpanel"
          id="mail-tabpanel-server"
          aria-labelledby="mail-tab-server"
          hidden={activeTab !== 'server'}
        >
          {activeTab === 'server' && <MailServerSection />}
        </Box>
        <Box
          role="tabpanel"
          id="mail-tabpanel-templates"
          aria-labelledby="mail-tab-templates"
          hidden={activeTab !== 'templates'}
        >
          {activeTab === 'templates' && <MailTemplatesSection />}
        </Box>
      </Box>
    </Box>
  )
}
