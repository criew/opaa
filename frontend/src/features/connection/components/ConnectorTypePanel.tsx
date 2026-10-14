import { useId, useState, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Drawer from '@mui/material/Drawer'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import type {
  CapabilityOverviewResponse,
  ConnectionProfileResponse,
  ConnectorTypeStateResponse,
} from '@/shared/api/types'
import { lockConnectorType, setConnectorProfileRequirement } from '../api/connectionProfileApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { connectionProfileRoute } from '@/shared/routes'
import SectionHead from '@/shared/ui/SectionHead'
import StatusLine from '@/shared/ui/StatusLine'
import { AccessBadge } from '@/features/capability'
import {
  accessBadgeLabel,
  currentAccess,
  objectPhrase,
  resultSentence,
} from '@/features/capability'
import ProfileRequirementDialog from './ProfileRequirementDialog'
import { confirmLock } from './connectorLock'
import { stockLabel } from './connectorTypeState'

function PanelSection({ title, children }: { title: string; children: ReactNode }) {
  const id = useId()
  return (
    <Box component="section" aria-labelledby={id}>
      <SectionHead id={id} component="h3">
        {title}
      </SectionHead>
      {children}
    </Box>
  )
}

interface ConnectorTypePanelProps {
  /** The source type to show; null keeps the panel closed. */
  state: ConnectorTypeStateResponse | null
  /** null while the profiles are unknown. */
  profiles: ConnectionProfileResponse[] | null
  /** Who may create libraries with an own address, where the type admits them. */
  ownAddressEntry: CapabilityOverviewResponse | null
  onClose: () => void
  onChanged: () => void
}

/**
 * One source type: its lock, its profiles, whether libraries may still use an own address and who
 * may create those. Each change asks first, as on the page before.
 */
export default function ConnectorTypePanel(props: ConnectorTypePanelProps) {
  const { state, onClose } = props
  return (
    <Drawer
      anchor="right"
      open={state !== null}
      onClose={onClose}
      slotProps={{
        paper: {
          role: 'dialog',
          'aria-labelledby': 'connector-type-panel-heading',
          sx: { width: { xs: '100%', sm: 520 }, display: 'flex', flexDirection: 'column' },
        },
      }}
    >
      {state && <PanelContent key={state.sourceType} {...props} state={state} />}
    </Drawer>
  )
}

function PanelContent({
  state,
  profiles,
  ownAddressEntry,
  onClose,
  onChanged,
}: ConnectorTypePanelProps & { state: ConnectorTypeStateResponse }) {
  const [requirementDialog, setRequirementDialog] = useState(false)
  const own = profiles?.filter((profile) => profile.sourceType === state.sourceType) ?? null
  const lockedProfiles = own?.filter((profile) => profile.locked).length ?? 0

  async function toggleLock() {
    const lock = !state.locked
    const remaining =
      !lock && lockedProfiles > 0
        ? `${lockedProfiles === 1 ? 'Ein Zugang' : `${lockedProfiles} Zugänge`} dieser Quellart ${lockedProfiles === 1 ? 'ist' : 'sind'} selbst gesperrt und ${lockedProfiles === 1 ? 'bleibt' : 'bleiben'} es; ${lockedProfiles === 1 ? 'seine' : 'ihre'} Bibliotheken laufen erst nach dem Entsperren ${lockedProfiles === 1 ? 'des Zugangs' : 'der Zugänge'} weiter. Die übrigen laufen ohne Neueinrichtung weiter.`
        : undefined
    if (!(await confirmLock(`die Quellart „${state.displayName}“`, lock, remaining))) return
    try {
      await lockConnectorType(state.sourceType, lock)
      notify(
        lock
          ? `Die Quellart „${state.displayName}“ ist gesperrt.`
          : `Die Sperre der Quellart „${state.displayName}“ ist aufgehoben.`,
        'success',
      )
      onChanged()
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Die Sperre ließ sich nicht ändern.', 'error')
    }
  }

  async function switchOff() {
    const confirmed = await confirmAction({
      question: `„Nur über Zugänge“ für die Quellart „${state.displayName}“ ausschalten?`,
      consequence:
        'Neue Bibliotheken dürfen dann wieder eine eigene Adresse nutzen, und eine Bibliothek lässt sich wieder von ihrem Zugang lösen. Wegen der Pflicht gesperrte Bibliotheken laufen ohne Neueinrichtung weiter. Das Ausschalten wird als Governance-Ereignis protokolliert.',
      confirmLabel: 'Ausschalten',
      tone: 'caution',
    })
    if (!confirmed) return
    try {
      await setConnectorProfileRequirement(state.sourceType, { required: false })
      notify(
        `Für die Quellart „${state.displayName}“ ist wieder eine eigene Adresse möglich.`,
        'success',
      )
      onChanged()
    } catch (err) {
      notify(
        err instanceof Error ? err.message : 'Die Einstellung ließ sich nicht ändern.',
        'error',
      )
    }
  }

  const ownAccess = ownAddressEntry ? currentAccess(ownAddressEntry) : null

  return (
    <>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 1,
          px: 3,
          pt: 2.5,
          pb: 2,
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        <Typography
          id="connector-type-panel-heading"
          component="h2"
          sx={{ flex: 1, fontSize: 18, fontWeight: 600, overflowWrap: 'anywhere' }}
        >
          {state.displayName}
        </Typography>
        <IconButton aria-label="Schließen" onClick={onClose} sx={{ mt: -0.5 }}>
          <CloseIcon />
        </IconButton>
      </Box>

      <Stack spacing={3.5} sx={{ flex: 1, overflowY: 'auto', px: 3, py: 2.5 }}>
        <PanelSection title="Zustand">
          <StatusLine
            tone={state.locked ? 'error' : 'success'}
            headline={state.locked ? 'Gesperrt' : 'Nutzbar'}
            detail={
              state.locked
                ? 'Die Quellart nimmt keine neuen Bibliotheken an, und ihre Bibliotheken laufen nicht. Der vorhandene Inhalt bleibt durchsuchbar.'
                : 'Bibliotheken dieser Quellart lassen sich anlegen und laufen.'
            }
          />
          <Button
            size="small"
            variant="outlined"
            color={state.locked ? 'primary' : 'error'}
            onClick={() => void toggleLock()}
            aria-label={
              state.locked
                ? `Sperre der Quellart ${state.displayName} aufheben`
                : `Quellart ${state.displayName} sperren`
            }
            sx={{ ml: 2 }}
          >
            {state.locked ? 'Entsperren' : 'Sperren'}
          </Button>
        </PanelSection>

        <PanelSection title="Zugänge">
          {state.profileSupport === 'FORBIDDEN' ? (
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
              Für diese Quellart sind in dieser Version keine Zugänge möglich.
            </Typography>
          ) : (
            <>
              {state.profileSupport === 'REQUIRED' ? (
                <Typography sx={{ fontSize: 13 }}>
                  Immer über Zugänge – das gibt die Quellart vor.
                </Typography>
              ) : (
                <Box>
                  <FormControlLabel
                    control={
                      <Switch
                        checked={state.profileRequired}
                        onChange={() =>
                          state.profileRequired ? void switchOff() : setRequirementDialog(true)
                        }
                        slotProps={{
                          input: { 'aria-label': `Nur über Zugänge für ${state.displayName}` },
                        }}
                      />
                    }
                    label="Nur über Zugänge"
                  />
                  <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                    {state.profileRequired
                      ? stockLabel(state.ownAddressStock)
                      : 'Bibliotheken dürfen einen Zugang wählen oder eine eigene Adresse eintragen.'}
                  </Typography>
                  {state.profileRequired && (
                    <Button
                      size="small"
                      onClick={() => setRequirementDialog(true)}
                      aria-label={`Bestandswahl für ${state.displayName} ändern`}
                      sx={{ mt: 0.5, ml: -1 }}
                    >
                      Bestandswahl ändern
                    </Button>
                  )}
                </Box>
              )}
              <Typography
                component="h4"
                sx={{ fontSize: 12, fontWeight: 600, color: 'text.secondary', mt: 2, mb: 0.75 }}
              >
                {own === null
                  ? 'Die Zugänge dieser Quellart konnten nicht geladen werden'
                  : own.length === 0
                    ? 'Noch kein Zugang dieser Quellart'
                    : own.length === 1
                      ? '1 Zugang dieser Quellart'
                      : `${own.length} Zugänge dieser Quellart`}
              </Typography>
              {own !== null && own.length > 0 && (
                <Box component="ul" sx={{ listStyle: 'none', p: 0, m: 0 }}>
                  {own.map((profile) => (
                    <Box component="li" key={profile.id} sx={{ py: 0.5 }}>
                      <Link component={RouterLink} to={connectionProfileRoute(profile.id)}>
                        {profile.name}
                      </Link>
                      {profile.locked && (
                        <Typography
                          component="span"
                          sx={{ fontSize: 12.5, color: 'text.secondary' }}
                        >
                          {' '}
                          · gesperrt
                        </Typography>
                      )}
                    </Box>
                  ))}
                </Box>
              )}
            </>
          )}
        </PanelSection>

        {ownAddressEntry && ownAccess && (
          <PanelSection title="Wer darf Bibliotheken mit eigener Adresse anlegen?">
            <AccessBadge tone={ownAccess.level} label={accessBadgeLabel(ownAccess)} />
            <Typography sx={{ fontSize: 13, mt: 1 }}>
              {resultSentence(ownAccess, objectPhrase(ownAddressEntry))}
            </Typography>
            <Link component={RouterLink} to="/admin/capabilities" sx={{ fontSize: 13.5 }}>
              Unter Anlegerechte ändern
            </Link>
          </PanelSection>
        )}
        {state.profileSupport !== 'FORBIDDEN' && own?.length === 0 && state.profileRequired && (
          <Alert severity="warning">
            Die Quellart ist nur über Zugänge nutzbar, hat aber keinen. Neue Bibliotheken sind damit
            nicht möglich.
          </Alert>
        )}
      </Stack>

      {requirementDialog && (
        <ProfileRequirementDialog
          open
          state={state}
          onClose={() => setRequirementDialog(false)}
          onChanged={onChanged}
        />
      )}
    </>
  )
}
