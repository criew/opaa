import { useEffect, useState } from 'react'
import { Navigate, Link as RouterLink, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import type { AccountResponse, LocalUserResponse } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { notify } from '../stores/notificationStore'
import { useUserAdminStore } from '../stores/userAdminStore'
import PageHeading from '../components/a11y/PageHeading'
import AreaPageHeader from '../components/AreaPageHeader'
import AccountFilterBar from '../components/admin/users/AccountFilterBar'
import AccountList from '../components/admin/users/AccountList'
import LocalAuthSettingsCard from '../components/admin/users/LocalAuthSettingsCard'
import LocalUserReviewNotice from '../components/admin/users/LocalUserReviewNotice'
import RoleChangeDialog from '../components/admin/users/RoleChangeDialog'
import UserFormDialog from '../components/admin/users/UserFormDialog'
import SetupLinkDialog, { type SetupLinkHandover } from '../components/admin/users/SetupLinkDialog'
import { contentWidth } from '../theme/tokens'
import GeneratedPasswordDialog, {
  type GeneratedPassword,
} from '../components/admin/users/GeneratedPasswordDialog'

export type UserManagementTab = 'accounts' | 'settings'

function isUserManagementTab(value: string | undefined): value is UserManagementTab {
  return value === 'accounts' || value === 'settings'
}

const tabs: Array<{ value: UserManagementTab; label: string }> = [
  { value: 'accounts', label: 'Konten' },
  { value: 'settings', label: 'Einstellungen' },
]

/** The prefill used while the settings are still loading; the server's own value replaces it. */
const FALLBACK_EXPIRY_DAYS = 365

/**
 * Der Bereich „Konten" (#1601): der stehende Hinweis zur Auflage, Suche und Filter, die Liste
 * aller Konten - lokale wie die der Identitätsanbieter, mit ihrer Herkunft an jeder Zeile - und
 * die Dialoge der Handlungen. Kein Auswertungspfad: Aktivität nur als Klasse und nur für lokale
 * Konten, keine Sortierung danach, kein Export, Seitengröße höchstens 50.
 */
function AccountsSection({ currentUserId }: { currentUserId: string | null }) {
  const accounts = useUserAdminStore((s) => s.accounts)
  const error = useUserAdminStore((s) => s.error)
  const summary = useUserAdminStore((s) => s.summary)
  const settings = useUserAdminStore((s) => s.settings)
  const loadAccounts = useUserAdminStore((s) => s.loadAccounts)
  const loadSummary = useUserAdminStore((s) => s.loadSummary)
  const loadSettings = useUserAdminStore((s) => s.loadSettings)
  const setFilters = useUserAdminStore((s) => s.setFilters)

  // `opening` is the dialog's key: every opening mounts a fresh dialog with a fresh draft.
  const [form, setForm] = useState<{
    open: boolean
    user?: LocalUserResponse
    opening: number
  }>({ open: false, opening: 0 })
  const [handover, setHandover] = useState<SetupLinkHandover | null>(null)
  const [generated, setGenerated] = useState<GeneratedPassword | null>(null)
  const [roleChange, setRoleChange] = useState<AccountResponse | null>(null)

  useEffect(() => {
    void loadAccounts()
    void loadSummary()
    // The create dialog prefills the expiry from the settings, which the settings area loads on
    // its own - here they are needed before the first „Konto anlegen".
    void loadSettings()
  }, [loadAccounts, loadSummary, loadSettings])

  return (
    <>
      <LocalUserReviewNotice
        summary={summary}
        // Der Sprung setzt jeden anderen Filter zurück (Review-Runde 1, LOW 10): Der Hinweis
        // nennt eine Zahl über alle lokalen Konten, und eine stehende Herkunfts-, Rollen- oder
        // Zustandsauswahl zeigte danach weniger Zeilen, als die Zahl verspricht.
        onShowWithoutExpiry={() =>
          void setFilters({
            review: 'WITHOUT_EXPIRY',
            status: null,
            role: null,
            query: '',
            providerType: 'LOCAL',
            providerId: null,
          })
        }
        onShowInvited={() =>
          void setFilters({
            status: 'INVITED',
            review: 'ALL',
            role: null,
            query: '',
            providerType: 'LOCAL',
            providerId: null,
          })
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {/* Die primäre Handlung im Kopf des Bereichs, nicht in der Filterzeile: fünf Filter und
          eine Schaltfläche in einer Reihe brechen um, und der Umbruch stellt die Handlung dann
          unter einen halben Filtersatz. */}
      <Stack
        direction="row"
        spacing={2}
        sx={{ alignItems: 'center', justifyContent: 'flex-end', mb: 1.5 }}
      >
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setForm((f) => ({ open: true, opening: f.opening + 1 }))}
        >
          Konto anlegen
        </Button>
      </Stack>
      <AccountFilterBar />
      <AccountList
        currentUserId={currentUserId}
        onEdit={(user) => setForm((f) => ({ open: true, user, opening: f.opening + 1 }))}
        onSetupLink={setHandover}
        onGeneratedPassword={(user, password) =>
          setGenerated({
            displayName: user.displayName,
            email: user.email,
            password,
          })
        }
        onChangeRole={setRoleChange}
      />

      <UserFormDialog
        key={form.opening}
        open={form.open}
        user={form.user}
        isSelf={form.user?.id === currentUserId}
        defaultExpiryDays={settings?.defaultExpiryDays ?? FALLBACK_EXPIRY_DAYS}
        onClose={() => setForm((f) => ({ ...f, open: false }))}
        onCreated={(created) => {
          setForm((f) => ({ ...f, open: false }))
          if (created.initialPassword) {
            setGenerated({
              displayName: created.user.displayName,
              email: created.user.email,
              password: created.initialPassword,
            })
          } else if (created.setupUrl) {
            setHandover({
              user: created.user,
              url: created.setupUrl,
              deliveryPath: created.deliveryPath,
              kind: 'INVITE',
            })
          } else {
            notify(`Die Einladung wurde an ${created.user.email} versendet.`, 'success')
          }
        }}
        onUpdated={(updated) => {
          setForm((f) => ({ ...f, open: false }))
          notify(`„${updated.displayName}“ wurde gespeichert.`, 'success')
        }}
      />
      <SetupLinkDialog handover={handover} onClose={() => setHandover(null)} />
      <GeneratedPasswordDialog generated={generated} onClose={() => setGenerated(null)} />
      {/* `key` wie beim UserFormDialog darüber: MUI unmountet beim Schließen nur die Kinder des
          Dialogs, nicht die Komponente. Ohne den Schlüssel trüge die zuletzt gewählte Rolle in das
          nächste geöffnete Konto — und „Speichern" wäre dort sofort aktiv. */}
      <RoleChangeDialog
        key={roleChange?.id ?? 'none'}
        account={roleChange}
        onClose={() => setRoleChange(null)}
      />
      {accounts.length > 0 && (
        <Typography sx={{ fontSize: 11.5, color: 'text.secondary', mt: 2 }}>
          Aktivität erscheint nur als Klasse und nur für lokale Konten; sie ist nicht sortierbar.
          Einen Export dieser Liste gibt es nicht.
        </Typography>
      )}
    </>
  )
}

/**
 * Die Benutzerverwaltung der Systemverwaltung (#1541, #1601, ADR-0033 Entscheidungen 4 und 11)
 * in zwei Bereichen: „Konten" führt alle Konten der Installation, lokale wie die der
 * Identitätsanbieter, mit ihrer Herkunft und den je Typ möglichen Handlungen; „Einstellungen"
 * die Schalter und Regeln der lokalen Anmeldung.
 *
 * Die Bereiche sind eigene Routen (`/admin/users/accounts`, `/admin/users/settings`) wie bei der
 * E-Mail-Seite: Ein Verweis soll im richtigen Bereich landen, und ein Neuladen ihn behalten.
 */
export default function UserManagementPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const currentUserId = useAuthStore((s) => s.user?.id ?? null)
  const { tab } = useParams()

  // A typo in the path is not silently reinterpreted: the address bar says what is shown.
  if (!isUserManagementTab(tab)) {
    return <Navigate to="/admin/users/accounts" replace />
  }
  const activeTab: UserManagementTab = tab

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Benutzer" gutterBottom />
        <Alert severity="info">
          Die Benutzerverwaltung wird von der Systemverwaltung gepflegt. Für Ihr Konto ist diese
          Seite nicht freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          title="Benutzer"
          description="Gilt für die gesamte Anwendung. Lokale Konten werden hier angelegt und geführt; Konten eines Identitätsanbieters erscheinen mit ihrer Rolle, ihr Lebenszyklus liegt beim Anbieter."
        />

        <Tabs
          value={activeTab}
          aria-label="Bereiche der Benutzerverwaltung"
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
              to={`/admin/users/${entry.value}`}
              id={`users-tab-${entry.value}`}
              aria-controls={`users-tabpanel-${entry.value}`}
            />
          ))}
        </Tabs>

        {/* Both panels exist so that every tab's aria-controls points at a real element. The
            inactive one is hidden and renders no children, so no request of the other area keeps
            running in the background. */}
        <Box
          role="tabpanel"
          id="users-tabpanel-accounts"
          aria-labelledby="users-tab-accounts"
          hidden={activeTab !== 'accounts'}
        >
          {activeTab === 'accounts' && <AccountsSection currentUserId={currentUserId} />}
        </Box>
        <Box
          role="tabpanel"
          id="users-tabpanel-settings"
          aria-labelledby="users-tab-settings"
          hidden={activeTab !== 'settings'}
        >
          {activeTab === 'settings' && <LocalAuthSettingsCard />}
        </Box>
      </Box>
    </Box>
  )
}
