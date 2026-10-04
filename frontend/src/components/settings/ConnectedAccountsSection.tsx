import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Link from '@mui/material/Link'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router'
import type {
  ConnectableProfile,
  ConnectedAccount,
  ConnectedAccountsOverview,
} from '../../types/api'
import { disconnectMyAccount, listMyConnectedAccounts } from '../../services/connectedAccountApi'
import { confirmAction } from '../../stores/confirmStore'
import { notify } from '../../stores/notificationStore'
import PageSection from '../PageSection'
import { AUTH_METHOD_LABELS } from '../admin/connections/connectionProfileLabels'
import ConnectAccountDialog, { type ConnectTarget } from './ConnectAccountDialog'
import { accountStateLabel, secretFormLabel } from './connectedAccountLabels'

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString('de-DE', { dateStyle: 'medium' })
}

function quotedNames(names: string[]): string {
  return names.map((name) => `„${name}“`).join(', ')
}

/** What disconnecting does, as the confirmation says it - the libraries first. */
function disconnectConsequence(account: ConnectedAccount): string {
  const parts = ['Ihre gespeicherten Zugangsdaten für diesen Zugang werden sofort gelöscht.']
  if (account.usedBy.length > 0) {
    const names = quotedNames(account.usedBy.map((library) => library.name))
    parts.push(
      account.usedBy.length === 1
        ? `Ihre Bibliothek ${names} ruht danach: Ihr Inhalt bleibt durchsuchbar, wird aber nicht mehr aktualisiert, bis Sie das Konto wieder verbinden.`
        : `Ihre Bibliotheken ${names} ruhen danach: Ihr Inhalt bleibt durchsuchbar, wird aber nicht mehr aktualisiert, bis Sie das Konto wieder verbinden.`,
    )
  } else {
    parts.push('Keine Ihrer Bibliotheken nutzt diese Verbindung.')
    if (!account.released) {
      parts.push(
        'Weil der Zugang für Sie nicht mehr freigegeben ist, können Sie danach hier kein Konto mehr verbinden.',
      )
    }
  }
  return parts.join(' ')
}

function signInLine(item: Pick<ConnectedAccount, 'secretForm' | 'authMethod'>): string {
  return item.secretForm ? secretFormLabel(item.secretForm) : AUTH_METHOD_LABELS[item.authMethod]
}

function AccountItem({
  account,
  onConnect,
  onDisconnect,
}: {
  account: ConnectedAccount
  onConnect: (target: ConnectTarget) => void
  onDisconnect: (account: ConnectedAccount) => void
}) {
  const state = accountStateLabel(account.state)
  const disconnected = account.state === 'DISCONNECTED'
  return (
    <Box
      component="li"
      sx={{ py: 2, borderBottom: 1, borderColor: 'divider', listStyle: 'none' }}
      data-testid={`connected-account-${account.profileId}`}
    >
      <Stack
        direction="row"
        spacing={1}
        useFlexGap
        sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 0.5 }}
      >
        <Typography component="h3" sx={{ fontSize: 14.5, fontWeight: 600 }}>
          {account.profileName}
        </Typography>
        <Chip size="small" color={state.color} label={state.label} />
        {!account.released && (
          <Chip size="small" variant="outlined" label="Nicht mehr freigegeben" />
        )}
        {!account.reconnectable && (
          <Chip size="small" color="error" variant="outlined" label="Zugang gesperrt" />
        )}
      </Stack>
      <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
        {account.accountLabel ? `Konto: ${account.accountLabel} · ` : ''}
        Anmeldung: {signInLine(account)} · verbunden seit {formatDate(account.connectedAt)}
        {account.reconnectedAt
          ? ` · zuletzt neu verbunden am ${formatDate(account.reconnectedAt)}`
          : ''}
      </Typography>
      <Typography component="div" sx={{ fontSize: 13, mt: 0.5 }}>
        {account.usedBy.length === 0 ? (
          <Box component="span" sx={{ color: 'text.secondary' }}>
            Von keiner Ihrer Bibliotheken genutzt.
          </Box>
        ) : (
          <>
            Genutzt von:{' '}
            {account.usedBy.map((library, index) => (
              <span key={library.id}>
                {index > 0 && ', '}
                <Link component={RouterLink} to={`/libraries/${library.id}`}>
                  {library.name}
                </Link>
              </span>
            ))}
          </>
        )}
      </Typography>
      {account.notice && (
        <Typography sx={{ fontSize: 13, mt: 1 }} data-testid="connected-account-notice">
          {account.notice}
          {account.responsible ? ` Zuständig: ${account.responsible}.` : ''}
        </Typography>
      )}
      <Stack direction="row" spacing={1} useFlexGap sx={{ mt: 1.5, flexWrap: 'wrap' }}>
        {account.secretForm && account.reconnectable && (
          <Button
            size="small"
            variant="outlined"
            aria-label={`${disconnected ? 'Konto verbinden' : 'Konto neu verbinden'}: ${account.profileName}`}
            onClick={() =>
              account.secretForm &&
              onConnect({
                profileId: account.profileId,
                name: account.profileName,
                secretForm: account.secretForm,
                accountLabel: account.accountLabel,
                reconnect: true,
              })
            }
          >
            {disconnected ? 'Verbinden' : 'Neu verbinden'}
          </Button>
        )}
        {!disconnected && (
          <Button
            size="small"
            color="error"
            aria-label={`Verbindung trennen: ${account.profileName}`}
            onClick={() => onDisconnect(account)}
          >
            Trennen
          </Button>
        )}
      </Stack>
      {!account.secretForm && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
          Diese Anmeldeart lässt sich hier noch nicht neu verbinden.
        </Typography>
      )}
    </Box>
  )
}

function ConnectableItem({
  profile,
  onConnect,
}: {
  profile: ConnectableProfile
  onConnect: (target: ConnectTarget) => void
}) {
  return (
    <Box
      component="li"
      sx={{
        py: 1.5,
        borderBottom: 1,
        borderColor: 'divider',
        listStyle: 'none',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 2,
        flexWrap: 'wrap',
      }}
    >
      <Box sx={{ minWidth: 0 }}>
        <Typography sx={{ fontSize: 14, fontWeight: 500 }}>{profile.name}</Typography>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Anmeldung: {signInLine(profile)}
        </Typography>
      </Box>
      {profile.secretForm ? (
        <Button
          size="small"
          variant="outlined"
          aria-label={`Konto verbinden: ${profile.name}`}
          onClick={() =>
            profile.secretForm &&
            onConnect({
              profileId: profile.profileId,
              name: profile.name,
              secretForm: profile.secretForm,
              reconnect: false,
            })
          }
        >
          Verbinden
        </Button>
      ) : (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          In dieser Version noch nicht verbindbar.
        </Typography>
      )}
    </Box>
  )
}

/**
 * The caller's own connected accounts: every existing connection with its state and notice, the
 * profiles they may connect anew, and who to ask when one is missing. Connecting is voluntary, so
 * nothing here urges it; the administration sees only numbers.
 */
export default function ConnectedAccountsSection() {
  const [overview, setOverview] = useState<ConnectedAccountsOverview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [target, setTarget] = useState<ConnectTarget | null>(null)

  const load = useCallback(
    () =>
      listMyConnectedAccounts()
        .then((loaded) => {
          setOverview(loaded)
          setError(null)
        })
        .catch((err: unknown) => {
          setError(
            err instanceof Error
              ? err.message
              : 'Die verbundenen Konten konnten nicht geladen werden.',
          )
        }),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  async function handleDisconnect(account: ConnectedAccount) {
    const confirmed = await confirmAction({
      question: `Verbindung zu „${account.profileName}“ trennen?`,
      consequence: disconnectConsequence(account),
      confirmLabel: 'Trennen',
      tone: 'danger',
    })
    if (!confirmed) return
    try {
      await disconnectMyAccount(account.profileId)
      notify(`Die Verbindung zu „${account.profileName}“ ist getrennt.`, 'success')
    } catch (err: unknown) {
      notify(
        err instanceof Error ? err.message : 'Die Verbindung konnte nicht getrennt werden.',
        'error',
      )
    }
    await load()
  }

  return (
    <>
      <PageSection
        title="Verbundene Konten"
        description="Mit einem verbundenen Konto hinterlegen Sie Ihre eigenen Zugangsdaten für einen Zugang, etwa ein App-Passwort. OPAA nutzt sie nur für Ihre eigenen privaten Bibliotheken; sie zeigen Inhalte, die Sie beim Anbieter selbst sehen dürfen. Ob Sie ein Konto verbinden, entscheiden Sie selbst."
      >
        {error && (
          <Alert
            severity="error"
            sx={{ mb: 2 }}
            action={
              <Button color="inherit" size="small" onClick={() => void load()}>
                Erneut laden
              </Button>
            }
          >
            {error}
          </Alert>
        )}
        {overview === null ? (
          !error && (
            <Box aria-busy="true">
              <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary', mb: 1 }}>
                Verbundene Konten werden geladen …
              </Typography>
              <Skeleton variant="rounded" height={120} />
            </Box>
          )
        ) : overview.accounts.length === 0 ? (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Sie haben kein Konto verbunden. Ein verbundenes Konto braucht nur, wer Inhalte aus einem
            eigenen Konto bei einem Anbieter in einer privaten Bibliothek durchsuchen möchte; alles
            andere in OPAA funktioniert ohne.
          </Typography>
        ) : (
          <Box component="ul" sx={{ m: 0, p: 0 }} aria-label="Ihre verbundenen Konten">
            {overview.accounts.map((account) => (
              <AccountItem
                key={account.profileId}
                account={account}
                onConnect={setTarget}
                onDisconnect={(item) => void handleDisconnect(item)}
              />
            ))}
          </Box>
        )}
      </PageSection>

      {overview && (
        <>
          <PageSection
            title="Weitere Zugänge"
            description="Zugänge, auf denen Sie ein Konto verbinden können."
          >
            {overview.connectable.length === 0 ? (
              <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                Für Sie ist derzeit kein weiterer Zugang zum Verbinden freigegeben.
              </Typography>
            ) : (
              <Box component="ul" sx={{ m: 0, p: 0 }} aria-label="Zugänge zum Verbinden">
                {overview.connectable.map((profile) => (
                  <ConnectableItem
                    key={profile.profileId}
                    profile={profile}
                    onConnect={setTarget}
                  />
                ))}
              </Box>
            )}
          </PageSection>

          <PageSection title="Warum fehlt mein Zugang?">
            <Typography sx={{ fontSize: 13.5 }}>{overview.missingAccess.text}</Typography>
            <Typography sx={{ fontSize: 13, color: 'text.secondary', mt: 0.5 }}>
              Zuständig: {overview.missingAccess.responsible}
            </Typography>
          </PageSection>
        </>
      )}

      {target && (
        <ConnectAccountDialog
          key={target.profileId}
          target={target}
          onClose={() => setTarget(null)}
          onConnected={(account) => {
            setTarget(null)
            notify(`Ihr Konto ist mit „${account.profileName}“ verbunden.`, 'success')
            void load()
          }}
        />
      )}
    </>
  )
}
