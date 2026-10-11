import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import type { ConnectionProfileResponse, ConnectorTypeStateResponse } from '../../../types/api'
import {
  listConnectionProfiles,
  listConnectorTypeStates,
} from '../../../services/connectionProfileApi'
import ConnectorTypeLockSection from './ConnectorTypeLockSection'

/**
 * Der Reiter „Quellarten“ der Anbindungen: Sperre und „Nur über Zugänge“ je Quellart. Die Zugänge
 * werden mitgeladen, weil das Entsperren einer Quellart nennt, welche ihrer Zugänge selbst gesperrt
 * bleiben.
 */
export default function ConnectorTypesTab() {
  const [states, setStates] = useState<ConnectorTypeStateResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [profiles, setProfiles] = useState<ConnectionProfileResponse[]>([])
  const [profilesError, setProfilesError] = useState<string | null>(null)
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
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Quellarten konnten nicht geladen werden.')
        }
      })
    void listConnectionProfiles()
      .then((loaded) => {
        if (cancelled) return
        setProfiles(loaded)
        setProfilesError(null)
      })
      .catch((err) => {
        if (!cancelled) {
          setProfilesError(
            err instanceof Error ? err.message : 'Zugänge konnten nicht geladen werden.',
          )
        }
      })
    return () => {
      cancelled = true
    }
  }, [reloadKey])

  return (
    <>
      {profilesError && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {`Die Zugänge konnten nicht geladen werden (${profilesError}). Die Rückfrage beim Entsperren nennt deshalb keine Zugänge, die selbst gesperrt bleiben.`}
        </Alert>
      )}
      <ConnectorTypeLockSection
        states={states}
        error={error}
        lockedProfileCount={(sourceType) =>
          profiles.filter((profile) => profile.sourceType === sourceType && profile.locked).length
        }
        onChanged={() => setReloadKey((key) => key + 1)}
      />
    </>
  )
}
