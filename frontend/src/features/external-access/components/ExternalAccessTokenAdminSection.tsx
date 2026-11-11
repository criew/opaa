import { useCallback, useEffect, useState } from 'react'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import FormField from '@/shared/ui/form/FormField'
import type {
  AdminExternalAccessTokenResponse,
  ExternalAccessTokenStatus,
} from '@/shared/api/types'
import {
  blockExternalAccessToken,
  blockExternalAccessTokensOfOwner,
  listAllExternalAccessTokens,
} from '../api/externalAccessApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import PageSection from '@/shared/ui/PageSection'
import RowMenu from '@/shared/ui/RowMenu'
import OwnTokensLink from './OwnTokensLink'
import StateDot from '@/shared/ui/StateDot'
import type { StatusTone } from '@/shared/ui/StatusLine'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
import {
  ADMIN_PURPOSE_HINT,
  BLOCK_ALL_CONSEQUENCE,
  BLOCK_CONSEQUENCE,
  TOKEN_STATUS_LABEL,
  formatDate,
} from './tokenLabels'
import EmptyState from '@/shared/ui/states/EmptyState'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

const STATUS_TONE: Record<ExternalAccessTokenStatus, StatusTone> = {
  ACTIVE: 'success',
  EXPIRED: 'neutral',
  REVOKED: 'neutral',
  BLOCKED: 'error',
}

/** The state as a dot before its word; the colour carries no meaning on its own. */
function TokenState({ status }: { status: ExternalAccessTokenStatus }) {
  return <StateDot tone={STATUS_TONE[status]} label={TOKEN_STATUS_LABEL[status]} />
}

function librariesOf(token: AdminExternalAccessTokenResponse): string {
  return token.libraries.map((library) => library.name).join(', ') || '—'
}

/** The draft's expiry filter; it is also the list's only time filter. */
const EXPIRING_WITHIN_DAYS = 30

const STATUS_OPTIONS: Array<{ value: ExternalAccessTokenStatus | 'ALL'; label: string }> = [
  { value: 'ALL', label: 'Alle Zustände' },
  { value: 'ACTIVE', label: TOKEN_STATUS_LABEL.ACTIVE },
  { value: 'EXPIRED', label: TOKEN_STATUS_LABEL.EXPIRED },
  { value: 'REVOKED', label: TOKEN_STATUS_LABEL.REVOKED },
  { value: 'BLOCKED', label: TOKEN_STATUS_LABEL.BLOCKED },
]

/** Both entries block for good (confirmation tone `danger`). */
function TokenMenu({
  token,
  onBlock,
  onBlockAllOfOwner,
}: {
  token: AdminExternalAccessTokenResponse
  onBlock: (token: AdminExternalAccessTokenResponse) => void
  onBlockAllOfOwner: (token: AdminExternalAccessTokenResponse) => void
}) {
  return (
    <RowMenu
      name={token.name}
      detail={`von ${token.ownerDisplayName}`}
      items={[
        token.status === 'ACTIVE' && {
          label: 'Token sperren',
          onClick: () => onBlock(token),
          danger: true,
        },
        {
          label: 'Alle Tokens dieser Person sperren',
          onClick: () => onBlockAllOfOwner(token),
          danger: true,
        },
      ]}
    />
  )
}

/**
 * The inventory list of all access tokens of the installation.
 *
 * It carries **no** usage date and offers **no** filter by person: a table filterable by
 * person with a usage date would be the person-related evaluation path whose
 * non-existence ADR-0068 promises. A revocation decision does not need it -
 * revocation happens because of an incident or an account life cycle.
 *
 * The revocation per person is therefore an action on a row and not an evaluation: it writes
 * instead of grouping the list by person.
 */
export default function ExternalAccessTokenAdminSection({
  onChanged,
}: {
  /** Called after a block, so figures elsewhere on the page follow. */
  onChanged?: () => void
} = {}) {
  const [tokens, setTokens] = useState<AdminExternalAccessTokenResponse[] | null>(null)
  const [status, setStatus] = useState<ExternalAccessTokenStatus | 'ALL'>('ALL')
  const [expiringSoon, setExpiringSoon] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // The state updates live in the promise's continuations, not in the effect body: a
  // synchronous setState there causes a cascade of renders (react-hooks).
  const load = useCallback(
    () =>
      listAllExternalAccessTokens({
        status: status === 'ALL' ? undefined : status,
        expiringWithinDays: expiringSoon ? EXPIRING_WITHIN_DAYS : undefined,
      })
        .then((loaded) => {
          setTokens(loaded)
          setError(null)
        })
        .catch((err: unknown) => {
          setError(
            err instanceof Error ? err.message : 'Die Tokenliste konnte nicht geladen werden.',
          )
        }),
    [status, expiringSoon],
  )

  useEffect(() => {
    void load()
  }, [load])

  async function handleBlock(token: AdminExternalAccessTokenResponse) {
    const confirmed = await confirmAction({
      question: `„${token.name}“ von ${token.ownerDisplayName} sperren?`,
      consequence: BLOCK_CONSEQUENCE,
      confirmLabel: 'Sperren',
      tone: 'danger',
    })
    if (!confirmed) return
    try {
      await blockExternalAccessToken(token.id)
      notify(`Das Token „${token.name}“ wurde gesperrt.`, 'success')
      await load()
      onChanged?.()
    } catch (err: unknown) {
      notify(
        err instanceof Error ? err.message : 'Das Token konnte nicht gesperrt werden.',
        'error',
      )
    }
  }

  async function handleBlockAllOfOwner(token: AdminExternalAccessTokenResponse) {
    const confirmed = await confirmAction({
      question: `Alle Zugangstokens von ${token.ownerDisplayName} sperren?`,
      consequence: BLOCK_ALL_CONSEQUENCE,
      confirmLabel: 'Sperren',
      tone: 'danger',
    })
    if (!confirmed) return
    try {
      const blocked = await blockExternalAccessTokensOfOwner(token.ownerUserId)
      notify(
        blocked === 1
          ? `Ein Token von ${token.ownerDisplayName} wurde gesperrt.`
          : `${blocked} Tokens von ${token.ownerDisplayName} wurden gesperrt.`,
        'success',
      )
      await load()
      onChanged?.()
    } catch (err: unknown) {
      notify(
        err instanceof Error ? err.message : 'Die Tokens konnten nicht gesperrt werden.',
        'error',
      )
    }
  }

  return (
    <PageSection
      title="Zugangstokens"
      description={
        <>
          Zugangstokens erzeugt jede Person selbst unter <OwnTokensLink />. Hier sehen Sie den
          Bestand und können Tokens sperren. {ADMIN_PURPOSE_HINT}
        </>
      }
    >
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 2, flexWrap: 'wrap' }}>
        <FormField
          select
          size="small"
          label="Zustand"
          hideLabel
          value={status}
          onChange={(e) => setStatus(e.target.value as ExternalAccessTokenStatus | 'ALL')}
          // The first filter of the list, where „Filter zurücksetzen" hands the focus back.
          slotProps={{ select: { SelectDisplayProps: { 'data-list-search': '' } as object } }}
          sx={{ minWidth: 200 }}
        >
          {STATUS_OPTIONS.map((option) => (
            <MenuItem key={option.value} value={option.value}>
              {option.label}
            </MenuItem>
          ))}
        </FormField>
        <FormControlLabel
          control={
            <Switch checked={expiringSoon} onChange={(e) => setExpiringSoon(e.target.checked)} />
          }
          label={`Läuft in ${EXPIRING_WITHIN_DAYS} Tagen ab`}
        />
      </Stack>

      {error ? (
        <LoadError
          message="Die Zugangstokens konnten nicht geladen werden."
          detail={error}
          onRetry={() => void load()}
        />
      ) : tokens === null ? (
        <LoadingState />
      ) : tokens.length === 0 ? (
        status !== 'ALL' || expiringSoon ? (
          <EmptyState
            kind="noMatch"
            onReset={() => {
              setStatus('ALL')
              setExpiringSoon(false)
            }}
          />
        ) : (
          <EmptyState message="Noch keine Zugangstokens." />
        )
      ) : (
        <AdminList
          label="Zugangstokens"
          rows={tokens}
          rowKey={(token) => token.id}
          rowLabel={(token) => token.name}
          minWidth={760}
          columns={[
            {
              id: 'owner',
              head: 'Besitzer',
              width: '17%',
              card: 'line',
              cell: (token) => token.ownerDisplayName,
            },
            {
              id: 'name',
              head: 'Name',
              width: '21%',
              card: 'title',
              cell: (token) => <ListText strong>{token.name}</ListText>,
            },
            { id: 'libraries', head: 'Bibliotheken', cell: (token) => librariesOf(token) },
            {
              id: 'created',
              head: 'Erstellt',
              width: 104,
              cell: (token) => formatDate(token.createdAt),
            },
            {
              id: 'expires',
              head: 'Läuft ab',
              width: 104,
              cell: (token) => formatDate(token.expiresAt),
            },
            {
              id: 'state',
              head: 'Zustand',
              width: 120,
              cell: (token) => <TokenState status={token.status} />,
            },
          ]}
          rowActions={(token) => (
            <TokenMenu
              token={token}
              onBlock={(row) => void handleBlock(row)}
              onBlockAllOfOwner={(row) => void handleBlockAllOfOwner(row)}
            />
          )}
        />
      )}
    </PageSection>
  )
}
