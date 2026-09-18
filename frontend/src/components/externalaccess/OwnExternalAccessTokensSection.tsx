import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import type {
  CreatedExternalAccessTokenResponse,
  ExternalAccessTokenLibraryResponse,
  OwnExternalAccessTokenResponse,
} from '../../types/api'
import {
  getExternalAccessChannelInfo,
  listOwnExternalAccessTokens,
  revokeOwnExternalAccessToken,
} from '../../services/externalAccessApi'
import { confirmAction } from '../../stores/confirmStore'
import { notify } from '../../stores/notificationStore'
import { fontFamily } from '../../theme/tokens'
import MetaBadge from '../MetaBadge'
import PageSection from '../PageSection'
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

/**
 * Eine ausgesetzte Auswahl wird gezeigt, nicht weggelassen: Die Person soll sehen, was ein neues
 * Token wieder enthalten müsste - und dass eine erneute Freigabe dieses Token nicht heilt.
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
              fontSize: 13,
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
                  sx={{ fontSize: 14, color: 'warning.main', flex: 'none' }}
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
 * Die eigenen Zugangstokens in den persönlichen Einstellungen (#1719): anlegen, einmal ansehen,
 * widerrufen.
 *
 * „Zuletzt benutzt" steht nur hier und nur als Datum; eine Zählung gibt es nirgends. Bei
 * geschlossenem Kanal bleibt die Verwaltung bedienbar - ein Notaus, der die Einrichtung
 * unerreichbar macht, wird im Zweifel nicht benutzt.
 */
export default function OwnExternalAccessTokensSection() {
  const [tokens, setTokens] = useState<OwnExternalAccessTokenResponse[] | null>(null)
  const [channelEnabled, setChannelEnabled] = useState<boolean | null>(null)
  const [maxLifetimeDays, setMaxLifetimeDays] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isCreating, setIsCreating] = useState(false)
  const [created, setCreated] = useState<CreatedExternalAccessTokenResponse | null>(null)

  // Die Zustandssetzung liegt in den Fortsetzungen der Zusage, nicht im Rumpf des Effekts: ein
  // synchrones setState dort erzeugt eine Kaskade von Renderdurchläufen (react-hooks).
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
          setTokens([])
          setError(
            err instanceof Error ? err.message : 'Die Zugangstokens konnten nicht geladen werden.',
          )
        }),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  async function handleRevoke(token: OwnExternalAccessTokenResponse) {
    const confirmed = await confirmAction({
      question: `Token „${token.name}“ widerrufen?`,
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
        description="Mit einem Zugangstoken durchsucht ein fremdes KI-Werkzeug - etwa Claude Code oder Cursor - die Bibliotheken, die Sie ihm erteilen. Es kann nie mehr als Sie selbst, und nur lesen und suchen."
        action={
          /* Bei geschlossenem Kanal weist die Ausstellung jede Anfrage ab und die Auswahlmenge ist
             leer - die Schaltfläche führte in eine Absage, statt zu einem Token. */
          <Tooltip title={channelEnabled === false ? CHANNEL_OFF_HINT : ''}>
            <Box component="span">
              <Button
                variant="contained"
                size="small"
                onClick={() => setIsCreating(true)}
                disabled={maxLifetimeDays === null || channelEnabled !== true}
              >
                Token erzeugen
              </Button>
            </Box>
          </Tooltip>
        }
      >
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        {channelEnabled === false && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            {CHANNEL_OFF_HINT}
          </Alert>
        )}

        {tokens === null ? (
          <Skeleton variant="rounded" height={160} />
        ) : tokens.length === 0 ? (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Sie haben noch kein Zugangstoken. „Token erzeugen“ legt eines an; die Auswahl der
            Bibliotheken treffen Sie dabei.
          </Typography>
        ) : (
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Name</TableCell>
                <TableCell>Präfix</TableCell>
                <TableCell>Bibliotheken</TableCell>
                <TableCell>Erstellt</TableCell>
                <TableCell>Läuft ab</TableCell>
                <TableCell>Zuletzt benutzt</TableCell>
                <TableCell>Zustand</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {tokens.map((token) => {
                const warning = expiryWarning(token.status, token.expiresAt)
                return (
                  <TableRow key={token.id}>
                    <TableCell sx={{ fontWeight: 500 }}>{token.name}</TableCell>
                    <TableCell sx={{ fontFamily: fontFamily.mono, fontSize: 12.5 }}>
                      {token.prefix}
                    </TableCell>
                    <TableCell>
                      <LibraryList libraries={token.libraries} />
                    </TableCell>
                    <TableCell>{formatDate(token.createdAt)}</TableCell>
                    <TableCell>
                      <Box>{formatDate(token.expiresAt)}</Box>
                      {warning && <MetaBadge>{warning}</MetaBadge>}
                    </TableCell>
                    <TableCell>{formatDate(token.lastUsedOn)}</TableCell>
                    <TableCell>
                      {TOKEN_STATUS_LABEL[token.status]}
                      {token.status === 'ACTIVE' && channelEnabled === false && (
                        <Box>
                          <MetaBadge>wirkt derzeit nicht</MetaBadge>
                        </Box>
                      )}
                    </TableCell>
                    <TableCell align="right">
                      {token.status === 'ACTIVE' && (
                        <Button
                          size="small"
                          color="error"
                          aria-label={`Token „${token.name}“ widerrufen`}
                          onClick={() => void handleRevoke(token)}
                        >
                          Widerrufen
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        )}
      </PageSection>

      {/* Je Vorgang neu montiert: Das Zurücksetzen des Entwurfs ist damit das Auswerfen der
          Komponente und kein Effekt, der denselben Zustand einen Durchlauf später herstellt. */}
      {isCreating && maxLifetimeDays !== null && channelEnabled === true && (
        <CreateExternalAccessTokenDialog
          tokenMaxLifetimeDays={maxLifetimeDays}
          onClose={() => setIsCreating(false)}
          onCreated={(response) => {
            setIsCreating(false)
            setCreated(response)
            void load()
          }}
        />
      )}
      <ExternalAccessTokenValueDialog created={created} onClose={() => setCreated(null)} />
    </>
  )
}
