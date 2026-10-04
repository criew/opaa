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
import {
  disconnectMyAccount,
  listMyConnectedAccounts,
  startAccountAuthorization,
} from '../../services/connectedAccountApi'
import { apiErrorStatus } from '../../services/apiErrorDetails'
import { leaveFor } from '../../services/leaveApp'
import { useSourceTypes } from '../../hooks/useSourceTypes'
import BusyButton from '../a11y/BusyButton'
import { confirmAction } from '../../stores/confirmStore'
import { notify } from '../../stores/notificationStore'
import PageSection from '../PageSection'
import { AUTH_METHOD_LABELS } from '../admin/connections/connectionProfileLabels'
import ConnectAccountDialog, { type ConnectTarget } from './ConnectAccountDialog'
import { accountStateLabel, secretFormLabel } from './connectedAccountLabels'

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString('de-DE', { dateStyle: 'medium' })
}

/** How far ahead of its end a consent is flagged, as the notification warns. */
const EXPIRY_WARNING_MS = 14 * 24 * 60 * 60 * 1000

function endsSoon(expiresAt: string | null | undefined): boolean {
  return expiresAt != null && new Date(expiresAt).getTime() - Date.now() <= EXPIRY_WARNING_MS
}

/** What connecting at the provider means, as the confirmation says it before leaving OPAA. */
const AUTHORIZE_CONSEQUENCE =
  'Sie werden zur Anmeldung beim Anbieter weitergeleitet und stimmen dort zu, dass OPAA Inhalte Ihres Kontos für Ihre privaten Bibliotheken lesen darf. Danach kommen Sie auf diese Seite zurück. Ihre Inhalte und Ihren Kontonamen sehen nur Sie; wann ein Konto verbunden, neu verbunden oder getrennt wurde, steht unter einem Pseudonym statt Ihres Namens im Verbindungsprotokoll der Revision.'

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
  sourceTypeName,
  onConnect,
  onAuthorize,
  onDisconnect,
  disconnecting,
  authorizing,
}: {
  account: ConnectedAccount
  sourceTypeName: string
  onConnect: (target: ConnectTarget) => void
  onAuthorize: (profileId: string, name: string, reconnect: boolean) => void
  onDisconnect: (account: ConnectedAccount) => void
  disconnecting: boolean
  authorizing: boolean
}) {
  const state = accountStateLabel(account.state)
  const disconnected = account.state === 'DISCONNECTED'
  const oauth = account.authMethod === 'OAUTH'
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
        {account.expiresAt && endsSoon(account.expiresAt) && (
          <Chip
            size="small"
            color="warning"
            label={`Zustimmung endet am ${formatDate(account.expiresAt)}`}
          />
        )}
      </Stack>
      <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
        Quellart: {sourceTypeName} ·{' '}
        {account.accountLabel ? `Konto: ${account.accountLabel} · ` : ''}
        Anmeldung: {signInLine(account)}
        {disconnected
          ? ' · getrennt'
          : ` · verbunden seit ${formatDate(account.connectedAt)}${
              account.reconnectedAt
                ? ` · zuletzt neu verbunden am ${formatDate(account.reconnectedAt)}`
                : ''
            }`}
        {account.expiresAt && !endsSoon(account.expiresAt)
          ? ` · Zustimmung gültig bis ${formatDate(account.expiresAt)}`
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
            aria-label={`Konto neu verbinden: ${account.profileName}`}
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
            Neu verbinden
          </Button>
        )}
        {oauth && account.reconnectable && (
          <BusyButton
            size="small"
            variant="outlined"
            aria-label={`Konto beim Anbieter neu verbinden: ${account.profileName}`}
            busy={authorizing}
            busyAnnouncement="Weiterleitung zum Anbieter"
            onClick={() => onAuthorize(account.profileId, account.profileName, true)}
          >
            Neu verbinden
          </BusyButton>
        )}
        {!disconnected && (
          <BusyButton
            size="small"
            color="error"
            aria-label={`Verbindung trennen: ${account.profileName}`}
            busy={disconnecting}
            busyAnnouncement="Verbindung wird getrennt"
            onClick={() => onDisconnect(account)}
          >
            Trennen
          </BusyButton>
        )}
      </Stack>
      {!account.secretForm && !oauth && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
          Diese Anmeldeart lässt sich hier noch nicht neu verbinden.
        </Typography>
      )}
    </Box>
  )
}

function ConnectableItem({
  profile,
  sourceTypeName,
  onConnect,
  onAuthorize,
  authorizing,
}: {
  profile: ConnectableProfile
  sourceTypeName: string
  onConnect: (target: ConnectTarget) => void
  onAuthorize: (profileId: string, name: string, reconnect: boolean) => void
  authorizing: boolean
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
          Quellart: {sourceTypeName} · Anmeldung: {signInLine(profile)}
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
      ) : profile.authMethod === 'OAUTH' ? (
        <BusyButton
          size="small"
          variant="outlined"
          aria-label={`Konto beim Anbieter verbinden: ${profile.name}`}
          busy={authorizing}
          busyAnnouncement="Weiterleitung zum Anbieter"
          onClick={() => onAuthorize(profile.profileId, profile.name, false)}
        >
          Verbinden
        </BusyButton>
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
  const [disconnecting, setDisconnecting] = useState<string | null>(null)
  const [authorizing, setAuthorizing] = useState<string | null>(null)
  const sourceTypes = useSourceTypes()
  // Known only once the source types are in; until then nothing is claimed either way.
  const noPersonalConnector =
    sourceTypes.loaded &&
    sourceTypes.error === null &&
    !sourceTypes.sourceTypes.some((type) =>
      type.signIns.some((signIn) => signIn.ownerships.includes('PERSON')),
    )
  const sourceTypeName = (key: string) =>
    sourceTypes.sourceTypes.find((type) => type.type === key)?.displayName ?? key

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

  /** Leaves for the provider's consent in this tab; the provider returns to the callback page. */
  async function handleAuthorize(profileId: string, name: string, reconnect: boolean) {
    if (authorizing !== null) return
    const confirmed = await confirmAction({
      question: reconnect ? `Konto bei „${name}“ neu verbinden?` : `Konto bei „${name}“ verbinden?`,
      consequence: AUTHORIZE_CONSEQUENCE,
      confirmLabel: 'Weiter zum Anbieter',
    })
    if (!confirmed) return
    setAuthorizing(profileId)
    try {
      const started = await startAccountAuthorization(profileId)
      leaveFor(started.authorizationUrl)
    } catch (err: unknown) {
      notify(
        err instanceof Error && err.message
          ? err.message
          : 'Die Anmeldung beim Anbieter ließ sich nicht starten.',
        'error',
      )
      setAuthorizing(null)
    }
  }

  async function handleDisconnect(account: ConnectedAccount) {
    if (disconnecting !== null) return
    const confirmed = await confirmAction({
      question: `Verbindung zu „${account.profileName}“ trennen?`,
      consequence: disconnectConsequence(account),
      confirmLabel: 'Trennen',
      tone: 'danger',
    })
    if (!confirmed) return
    setDisconnecting(account.profileId)
    try {
      await disconnectMyAccount(account.profileId)
      notify(`Die Verbindung zu „${account.profileName}“ ist getrennt.`, 'success')
    } catch (err: unknown) {
      // 404: already disconnected, e.g. from another tab - the wish is fulfilled
      if (apiErrorStatus(err) === 404) {
        notify(`Die Verbindung zu „${account.profileName}“ ist getrennt.`, 'success')
      } else {
        notify(
          err instanceof Error ? err.message : 'Die Verbindung konnte nicht getrennt werden.',
          'error',
        )
      }
    }
    await load()
    setDisconnecting(null)
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
                sourceTypeName={sourceTypeName(account.sourceType)}
                onConnect={setTarget}
                onAuthorize={(id, name, reconnect) => void handleAuthorize(id, name, reconnect)}
                onDisconnect={(item) => void handleDisconnect(item)}
                disconnecting={disconnecting === account.profileId}
                authorizing={authorizing === account.profileId}
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
                {noPersonalConnector
                  ? 'In dieser Installation bietet noch keine Quellart verbundene Konten an. Deshalb gibt es hier noch nichts zu verbinden.'
                  : 'Derzeit gibt es keinen weiteren Zugang, auf dem Sie ein Konto verbinden können.'}
              </Typography>
            ) : (
              <Box component="ul" sx={{ m: 0, p: 0 }} aria-label="Zugänge zum Verbinden">
                {overview.connectable.map((profile) => (
                  <ConnectableItem
                    key={profile.profileId}
                    profile={profile}
                    sourceTypeName={sourceTypeName(profile.sourceType)}
                    onConnect={setTarget}
                    onAuthorize={(id, name, reconnect) => void handleAuthorize(id, name, reconnect)}
                    authorizing={authorizing === profile.profileId}
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
