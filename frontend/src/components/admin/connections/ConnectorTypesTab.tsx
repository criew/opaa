import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import type {
  CapabilityOverviewResponse,
  ConnectionProfileResponse,
  ConnectorTypeStateResponse,
} from '../../../types/api'
import { getCapabilityOverview } from '../../../services/capabilityAdminApi'
import {
  listConnectionProfiles,
  listConnectorTypeStates,
} from '../../../services/connectionProfileApi'
import StatusLine from '../../StatusLine'
import { ListLoading } from '../list/AdminList'
import ConnectorTypeOverview from './ConnectorTypeOverview'
import ConnectorTypePanel from './ConnectorTypePanel'

/** The query parameter that opens the panel of one source type. */
export const SOURCE_TYPE_PARAM = 'sourceType'

function message(err: unknown, fallback: string): string {
  return err instanceof Error ? err.message : fallback
}

/**
 * Der Reiter „Quellarten“ der Anbindungen: je Quellart, ob sie genutzt werden darf, wie sie zu
 * Zugängen steht und wie viele sie hat. Sperre, „Nur über Zugänge“ und die Zugänge der Quellart
 * stehen im Seitenpanel; `?sourceType=<key>` öffnet es.
 */
export default function ConnectorTypesTab() {
  const [searchParams, setSearchParams] = useSearchParams()
  const openKey = searchParams.get(SOURCE_TYPE_PARAM)
  const [states, setStates] = useState<ConnectorTypeStateResponse[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [profiles, setProfiles] = useState<ConnectionProfileResponse[]>([])
  const [profilesError, setProfilesError] = useState<string | null>(null)
  const [overview, setOverview] = useState<CapabilityOverviewResponse[] | null>(null)
  const [overviewError, setOverviewError] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let cancelled = false
    void listConnectorTypeStates()
      .then((loaded) => {
        if (cancelled) return
        setStates(loaded)
        setError(null)
      })
      .catch((err) => {
        if (!cancelled) setError(message(err, 'Quellarten konnten nicht geladen werden.'))
      })
    void listConnectionProfiles()
      .then((loaded) => {
        if (cancelled) return
        setProfiles(loaded)
        setProfilesError(null)
      })
      .catch((err) => {
        if (!cancelled) setProfilesError(message(err, 'Zugänge konnten nicht geladen werden.'))
      })
    void getCapabilityOverview()
      .then((loaded) => {
        if (cancelled) return
        setOverview(loaded)
        setOverviewError(null)
      })
      .catch((err) => {
        if (!cancelled) {
          setOverviewError(message(err, 'Die Anlegerechte konnten nicht geladen werden.'))
        }
      })
    return () => {
      cancelled = true
    }
  }, [reloadKey])

  function openPanel(sourceType: string | null) {
    const next = new URLSearchParams(searchParams)
    if (sourceType) next.set(SOURCE_TYPE_PARAM, sourceType)
    else next.delete(SOURCE_TYPE_PARAM)
    setSearchParams(next, { replace: true, preventScrollReset: true })
  }

  const entries = (states ?? []).map((state) => ({
    state,
    profileCount: profiles.filter((profile) => profile.sourceType === state.sourceType).length,
  }))
  const locked = entries.filter((entry) => entry.state.locked).length
  const opened = states?.find((state) => state.sourceType === openKey) ?? null
  const ownAddressEntry =
    opened && overview
      ? (overview.find(
          (entry) =>
            entry.capability === 'CREATE_CONNECTOR_LIBRARY' &&
            entry.scope === `TYPE:${opened.sourceType}`,
        ) ?? null)
      : null

  return (
    <>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {profilesError && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {`Die Zugänge konnten nicht geladen werden (${profilesError}). Die Zahlen und die Rückfrage beim Entsperren nennen deshalb keine Zugänge.`}
        </Alert>
      )}
      {overviewError && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {`Wer Bibliotheken mit eigener Adresse anlegen darf, konnte nicht geladen werden (${overviewError}).`}
        </Alert>
      )}
      {states === null ? (
        !error && <ListLoading label="Quellarten werden geladen" />
      ) : (
        <>
          <Box sx={{ mb: 3 }}>
            <StatusLine
              component="h2"
              label="Zustand der Quellarten"
              tone={locked > 0 ? 'warning' : 'success'}
              headline={
                locked === 0
                  ? 'Alle Quellarten sind nutzbar'
                  : locked === 1
                    ? '1 Quellart ist gesperrt'
                    : `${locked} Quellarten sind gesperrt`
              }
              detail="Eine gesperrte Quellart nimmt keine neuen Bibliotheken an, und ihre Bibliotheken laufen nicht; der Inhalt bleibt durchsuchbar. „Nur über Zugänge“ lässt für eine Quellart keine eigene Adresse mehr zu."
            />
          </Box>
          <ConnectorTypeOverview entries={entries} onOpen={openPanel} />
        </>
      )}

      <ConnectorTypePanel
        state={opened}
        profiles={profiles}
        ownAddressEntry={ownAddressEntry}
        onClose={() => openPanel(null)}
        onChanged={() => setReloadKey((key) => key + 1)}
      />
    </>
  )
}
