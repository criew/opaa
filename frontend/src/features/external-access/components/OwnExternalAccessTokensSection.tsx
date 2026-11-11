import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import type {
  CreatedExternalAccessTokenResponse,
  ExternalAccessTokenLibraryResponse,
  OwnExternalAccessTokenResponse,
} from '@/shared/api/types'
import {
  getExternalAccessChannelInfo,
  listOwnExternalAccessTokens,
  revokeOwnExternalAccessToken,
} from '../api/externalAccessApi'
import CreateButton from '@/shared/ui/CreateButton'
import { confirmAction } from '@/shared/stores/confirmStore'
import { consumeRecentLoginReturn } from '@/shared/session/recentLogin'
import { notify } from '@/shared/stores/notificationStore'
import { fontSize, iconSize } from '@/shared/theme/tokens'
import MetaBadge from '@/shared/ui/MetaBadge'
import PageSection from '@/shared/ui/PageSection'
import RowMenu from '@/shared/ui/RowMenu'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
import CreateExternalAccessTokenDialog from './CreateExternalAccessTokenDialog'
import ExternalAccessTokenValueDialog from './ExternalAccessTokenValueDialog'
import {
  CHANNEL_OFF_HINT,
  REVOKE_CONSEQUENCE,
  SUSPENDED_LIBRARY_HINT,
  TOKEN_STATUS_LABEL,
  expiryWarning,
  formatDate,
} from './tokenLabels'
import EmptyState from '@/shared/ui/states/EmptyState'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

/**
 * A suspended selection is shown, not omitted: the person should see what a new
 * token would have to contain again - and that a renewed release does not heal this token.
 */
function LibraryList({ libraries }: { libraries: ExternalAccessTokenLibraryResponse[] }) {
  if (libraries.length === 0) return <>—</>
  return (
    <Stack spacing={0.25}>
      {libraries.map((library) => (
        <Stack key={library.id} direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
          <Typography
            component="span"
            sx={{
              fontSize: fontSize.text,
              color: library.suspended ? 'text.secondary' : 'text.primary',
            }}
          >
            {library.name}
          </Typography>
          {library.suspended && (
            <Tooltip title={SUSPENDED_LIBRARY_HINT}>
              <Stack direction="row" spacing={0.25} component="span" sx={{ alignItems: 'center' }}>
                <WarningAmberIcon
                  aria-hidden="true"
                  sx={{ fontSize: iconSize.xs, color: 'warning.main', flex: 'none' }}
                />
                <MetaBadge>Freigabe ausgesetzt</MetaBadge>
              </Stack>
            </Tooltip>
          )}
        </Stack>
      ))}
    </Stack>
  )
}

/**
 * The own access tokens in the personal settings: create, view once,
 * revoke.
 *
 * „Zuletzt benutzt" appears only here and only as a date; there is no count anywhere. With a
 * closed channel the management stays usable - an emergency stop that makes the setup
 * unreachable is, when in doubt, not used.
 */
export default function OwnExternalAccessTokensSection() {
  const [tokens, setTokens] = useState<OwnExternalAccessTokenResponse[] | null>(null)
  const [channelEnabled, setChannelEnabled] = useState<boolean | null>(null)
  const [maxLifetimeDays, setMaxLifetimeDays] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isCreating, setIsCreating] = useState(false)
  const [created, setCreated] = useState<CreatedExternalAccessTokenResponse | null>(null)
  const [afterProviderSignIn, setAfterProviderSignIn] = useState(false)

  // The state updates live in the promise's continuations, not in the effect body: a
  // synchronous setState there causes a cascade of renders (react-hooks).
  const load = useCallback(
    () =>
      Promise.all([getExternalAccessChannelInfo(), listOwnExternalAccessTokens()])
        .then(([info, own]) => {
          setChannelEnabled(info.enabled)
          setMaxLifetimeDays(info.tokenMaxLifetimeDays)
          setTokens(own)
          setError(null)
        })
        .catch((err: unknown) => {
          setError(
            err instanceof Error ? err.message : 'Die Zugangstokens konnten nicht geladen werden.',
          )
        }),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  // Back from a sign-in forced at the provider for an issuance: the creation dialog opens again.
  // Under StrictMode the effect runs twice; the return is consumed by the first run only.
  useEffect(() => {
    if (!consumeRecentLoginReturn()) return
    void Promise.resolve().then(() => {
      setAfterProviderSignIn(true)
      setIsCreating(true)
    })
  }, [])

  async function handleRevoke(token: OwnExternalAccessTokenResponse) {
    const confirmed = await confirmAction({
      question: `„${token.name}“ widerrufen?`,
      consequence: REVOKE_CONSEQUENCE,
      confirmLabel: 'Widerrufen',
      tone: 'danger',
    })
    if (!confirmed) return
    try {
      await revokeOwnExternalAccessToken(token.id)
      notify(`Das Token „${token.name}“ wurde widerrufen.`, 'success')
      await load()
    } catch (err: unknown) {
      notify(
        err instanceof Error ? err.message : 'Das Token konnte nicht widerrufen werden.',
        'error',
      )
    }
  }

  return (
    <>
      <PageSection
        title="Zugangstokens"
        description="Mit einem Zugangstoken erhält ein externes KI-Werkzeug Zugriff auf Wissensbibliotheken, die Sie mit ihm teilen."
      >
        {/* With a closed channel the issuance rejects every request and the selectable set is
           empty - the button would lead to a refusal instead of a token. */}
        <CreateButton
          onClick={() => setIsCreating(true)}
          disabled={maxLifetimeDays === null || channelEnabled !== true}
          disabledReason={channelEnabled === false ? CHANNEL_OFF_HINT : undefined}
        >
          Token erzeugen
        </CreateButton>
        {channelEnabled === false && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            {CHANNEL_OFF_HINT}
          </Alert>
        )}

        {error ? (
          <LoadError
            message="Die Zugangstokens konnten nicht geladen werden."
            detail={error}
            onRetry={() => void load()}
          />
        ) : tokens === null ? (
          <LoadingState />
        ) : tokens.length === 0 ? (
          <EmptyState message="Noch keine Zugangstokens. „Token erzeugen“ legt eines an; die Auswahl der Bibliotheken treffen Sie dabei." />
        ) : (
          <AdminList
            label="Zugangstokens"
            rows={tokens}
            rowKey={(token) => token.id}
            rowLabel={(token) => token.name}
            minWidth={900}
            columns={[
              {
                id: 'name',
                head: 'Name',
                cell: (token) => (
                  <>
                    <ListText strong>{token.name}</ListText>
                    <ListText mono secondary>
                      {token.prefix}
                    </ListText>
                  </>
                ),
              },
              {
                id: 'libraries',
                head: 'Bibliotheken',
                width: '26%',
                cell: (token) => <LibraryList libraries={token.libraries} />,
              },
              {
                id: 'created',
                head: 'Erstellt',
                width: 104,
                cell: (token) => formatDate(token.createdAt),
              },
              {
                id: 'expires',
                head: 'Läuft ab',
                width: 136,
                cell: (token) => {
                  const warning = expiryWarning(token.status, token.expiresAt)
                  return (
                    <>
                      <Box>{formatDate(token.expiresAt)}</Box>
                      {warning && <MetaBadge>{warning}</MetaBadge>}
                    </>
                  )
                },
              },
              {
                id: 'lastUsed',
                head: 'Zuletzt benutzt',
                width: 148,
                cell: (token) => formatDate(token.lastUsedOn),
              },
              {
                id: 'state',
                head: 'Zustand',
                width: 152,
                cell: (token) => (
                  <>
                    <Box>{TOKEN_STATUS_LABEL[token.status]}</Box>
                    {token.status === 'ACTIVE' && channelEnabled === false && (
                      <MetaBadge>wirkt derzeit nicht</MetaBadge>
                    )}
                  </>
                ),
              },
            ]}
            rowActions={(token) =>
              token.status === 'ACTIVE' ? (
                <RowMenu
                  name={token.name}
                  items={[
                    {
                      label: 'Widerrufen',
                      onClick: () => void handleRevoke(token),
                      danger: true,
                    },
                  ]}
                />
              ) : null
            }
          />
        )}
      </PageSection>

      {/* Mounted anew per operation: resetting the draft is thus unmounting the
         component and not an effect that produces the same state one render later. */}
      {isCreating && maxLifetimeDays !== null && channelEnabled === true && (
        <CreateExternalAccessTokenDialog
          tokenMaxLifetimeDays={maxLifetimeDays}
          afterProviderSignIn={afterProviderSignIn}
          onClose={() => {
            setIsCreating(false)
            setAfterProviderSignIn(false)
          }}
          onCreated={(response) => {
            setIsCreating(false)
            setAfterProviderSignIn(false)
            setCreated(response)
            void load()
          }}
        />
      )}
      <ExternalAccessTokenValueDialog created={created} onClose={() => setCreated(null)} />
    </>
  )
}
