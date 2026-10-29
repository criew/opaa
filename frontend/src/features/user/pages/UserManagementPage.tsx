import { useEffect, useState } from 'react'
import { Navigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import AddIcon from '@mui/icons-material/Add'
import BadgeOutlinedIcon from '@mui/icons-material/BadgeOutlined'
import type { AccountResponse, LocalUserResponse } from '@/shared/api/types'
import { useAuthStore } from '@/shared/session/authStore'
import { notify } from '@/shared/stores/notificationStore'
import { useUserAdminStore } from '../stores/userAdminStore'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import AreaTabs from '@/shared/ui/AreaTabs'
import AccountFilterBar from '../components/AccountFilterBar'
import AccountList from '../components/AccountList'
import LocalAuthSettingsCard from '../components/LocalAuthSettingsCard'
import LocalUserReviewHint from '../components/LocalUserReviewHint'
import RoleChangeDialog from '../components/RoleChangeDialog'
import UserFormDialog from '../components/UserFormDialog'
import SetupLinkDialog, { type SetupLinkHandover } from '../components/SetupLinkDialog'
import HandoverDialog, { type HandoverTarget } from '../components/HandoverDialog'
import HandoverLinkDialog from '../components/HandoverLinkDialog'
import { contentWidth } from '@/shared/theme/tokens'
import GeneratedPasswordDialog, {
  type GeneratedPassword,
} from '../components/GeneratedPasswordDialog'
import { ROLE_CHANGE_RETURNED_HINT } from '../components/localUserLabels'
import { useProviderReturn } from '@/shared/session/useRecentLoginGate'

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
 * The „Konten" area: the hint about the requirement, search and filters, the list
 * of all accounts - local ones as well as those of the identity providers, with their origin on
 * every row - and the dialogs of the actions. No evaluation path: no activity in the list, no
 * export, page size at most 50.
 */
function AccountsSection({ currentUserId }: { currentUserId: string | null }) {
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
  const [handoverTarget, setHandoverTarget] = useState<HandoverTarget | null>(null)
  const [handoverLink, setHandoverLink] = useState<{
    user: LocalUserResponse
    url: string
  } | null>(null)

  // Back from a sign-in forced at the provider: the dialog that asked for it is gone with the page,
  // so the person makes the change again. Taken here, so no other page reads the return as its own;
  // the gate holds it until that next attempt (useRecentLoginGate).
  useProviderReturn(ROLE_CHANGE_RETURNED_HINT)

  useEffect(() => {
    void loadAccounts()
    void loadSummary()
    // The create dialog prefills the expiry from the settings, which the settings area loads on
    // its own - here they are needed before the first „Konto anlegen".
    void loadSettings()
  }, [loadAccounts, loadSummary, loadSettings])

  return (
    <>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {/* The primary action in the head of the area, not in the filter row: five filters and
         a button in one row wrap, and the wrap then puts the action
         below half a set of filters. To its left, flush with the table, the hint about the
         requirement – only if there is one. */}
      <Stack
        direction="row"
        spacing={2}
        sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 1.5 }}
      >
        <Box sx={{ minWidth: 0 }}>
          <LocalUserReviewHint
            summary={summary}
            // The jump resets every other filter: the hint
            // names a number across all local accounts, and a standing origin, role or
            // state selection would then show fewer rows than the number promises.
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
        </Box>
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
        onHandover={(user) => setHandoverTarget({ user })}
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
      {/* `key` as for the UserFormDialog: provider choice and reason belong to one account. */}
      <HandoverDialog
        key={`handover-${handoverTarget?.user.id ?? 'none'}`}
        target={handoverTarget}
        onClose={() => setHandoverTarget(null)}
        onLinkDisplayed={(user, url) => setHandoverLink({ user, url })}
      />
      <HandoverLinkDialog handover={handoverLink} onClose={() => setHandoverLink(null)} />
      <GeneratedPasswordDialog generated={generated} onClose={() => setGenerated(null)} />
      {/* `key` as for the UserFormDialog above: MUI only unmounts the dialog's children on close,
         not the component. Without the key the last chosen role would carry over into the
         next opened account — and „Speichern" would be active there at once. */}
      <RoleChangeDialog
        key={roleChange?.id ?? 'none'}
        account={roleChange}
        onClose={() => setRoleChange(null)}
      />
    </>
  )
}

/**
 * The system administration's user management (ADR-0033 Entscheidungen 4 and 11)
 * in two areas: „Konten" lists all accounts of the installation, local ones as well as those of
 * the identity providers, with their origin and the actions possible per type; „Einstellungen"
 * the switches and rules of the local login.
 *
 * The areas are routes of their own (`/admin/users/accounts`, `/admin/users/settings`) as on the
 * mail page: a link should land in the right area, and a reload should keep it.
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
          icon={BadgeOutlinedIcon}
          title="Benutzer"
          description="Alle Konten, die sich bei OPAA anmelden können. Lokale Konten pflegen Sie hier, bei Konten eines Identitätsanbieters nur die Rolle."
        />

        <AreaTabs
          tabs={tabs}
          value={activeTab}
          href={(value) => `/admin/users/${value}`}
          label="Bereiche der Benutzerverwaltung"
          idPrefix="users"
        >
          {(value) =>
            value === 'accounts' ? (
              <AccountsSection currentUserId={currentUserId} />
            ) : (
              <LocalAuthSettingsCard />
            )
          }
        </AreaTabs>
      </Box>
    </Box>
  )
}
