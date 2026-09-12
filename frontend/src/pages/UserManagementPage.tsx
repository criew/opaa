import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { LocalUserResponse } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { notify } from '../stores/notificationStore'
import { useUserAdminStore } from '../stores/userAdminStore'
import PageHeading from '../components/a11y/PageHeading'
import GlobalScopeNote from '../components/GlobalScopeNote'
import SectionHead from '../components/SectionHead'
import LocalAuthSettingsCard from '../components/admin/users/LocalAuthSettingsCard'
import LocalUserFilterBar from '../components/admin/users/LocalUserFilterBar'
import LocalUserList from '../components/admin/users/LocalUserList'
import LocalUserReviewNotice from '../components/admin/users/LocalUserReviewNotice'
import UserFormDialog from '../components/admin/users/UserFormDialog'
import SetupLinkDialog, { type SetupLinkHandover } from '../components/admin/users/SetupLinkDialog'
import GeneratedPasswordDialog, {
  type GeneratedPassword,
} from '../components/admin/users/GeneratedPasswordDialog'

/** The prefill used while the settings are still loading; the server's own value replaces it. */
const FALLBACK_EXPIRY_DAYS = 365

/**
 * Die Benutzerverwaltung der Systemverwaltung (#1541, ADR-0033 Entscheidungen 4 und 11): die
 * Schalter der lokalen Anmeldung, der stehende Hinweis zur Auflage und die Liste der **lokalen**
 * Konten mit ihren Handlungen.
 *
 * Die Liste führt ausdrücklich keine Konten eines Identitätsanbieters und ist kein Auswertungspfad:
 * Aktivität nur als Klasse, keine Sortierung danach, kein Export, Seitengröße höchstens 50. Die
 * Rolle eines OIDC-Kontos wird nicht in dieser Sicht verwaltet.
 */
export default function UserManagementPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const currentUserId = useAuthStore((s) => s.user?.id ?? null)
  const users = useUserAdminStore((s) => s.users)
  const error = useUserAdminStore((s) => s.error)
  const summary = useUserAdminStore((s) => s.summary)
  const settings = useUserAdminStore((s) => s.settings)
  const loadUsers = useUserAdminStore((s) => s.loadUsers)
  const loadSummary = useUserAdminStore((s) => s.loadSummary)
  const setFilters = useUserAdminStore((s) => s.setFilters)

  // `opening` is the dialog's key: every opening mounts a fresh dialog with a fresh draft.
  const [form, setForm] = useState<{
    open: boolean
    user?: LocalUserResponse
    opening: number
  }>({ open: false, opening: 0 })
  const [handover, setHandover] = useState<SetupLinkHandover | null>(null)
  const [generated, setGenerated] = useState<GeneratedPassword | null>(null)

  useEffect(() => {
    if (!isSystemAdmin) return
    void loadUsers()
    void loadSummary()
  }, [isSystemAdmin, loadUsers, loadSummary])

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: 4, maxWidth: 720 }}>
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
      <Box sx={{ maxWidth: 1180 }}>
        <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2, mb: 2.5, flexWrap: 'wrap' }}>
          <PageHeading title="Benutzer" />
          {summary && (
            <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
              {summary.total === 1 ? '1 lokales Konto' : `${summary.total} lokale Konten`}
            </Typography>
          )}
        </Box>
        <GlobalScopeNote>
          Gilt für die gesamte Anwendung. Diese Liste führt ausschließlich lokale Konten; Konten
          eines Identitätsanbieters werden über den Anbieter geführt.
        </GlobalScopeNote>

        <LocalAuthSettingsCard />

        <LocalUserReviewNotice
          summary={summary}
          // Der Sprung setzt jeden anderen Filter zurück (Review-Runde 1, LOW 10): Der Hinweis
          // nennt eine Zahl über alle Konten, und eine stehende Rollen- oder Zustandsauswahl
          // zeigte danach weniger Zeilen, als die Zahl verspricht.
          onShowWithoutExpiry={() =>
            void setFilters({ review: 'WITHOUT_EXPIRY', status: null, role: null, query: '' })
          }
          onShowInvited={() =>
            void setFilters({ status: 'INVITED', review: 'ALL', role: null, query: '' })
          }
        />

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        <Box component="section" aria-labelledby="local-users-title">
          <SectionHead id="local-users-title">Lokale Konten</SectionHead>
          <LocalUserFilterBar
            onCreate={() => setForm((f) => ({ open: true, opening: f.opening + 1 }))}
          />
          <LocalUserList
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
          />
        </Box>

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
        {users.length > 0 && (
          <Typography sx={{ fontSize: 11.5, color: 'text.secondary', mt: 2 }}>
            Aktivität erscheint nur als Klasse und ist nicht sortierbar; einen Export dieser Liste
            gibt es nicht.
          </Typography>
        )}
      </Box>
    </Box>
  )
}
