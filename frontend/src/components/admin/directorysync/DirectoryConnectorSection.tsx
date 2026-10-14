import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { OidcProviderResponse } from '@/shared/api/types'
import {
  deleteDirectoryConnector,
  saveDirectoryConnector,
  testDirectoryConnector,
} from '../../../services/directorySyncApi'
import { apiErrorMessage } from '@/shared/api/apiErrorDetails'
import { confirmAction } from '../../../stores/confirmStore'
import { notify } from '../../../stores/notificationStore'
import KeyValueList from '../../KeyValueList'
import { DIRECTORY_SYNC_CONFLICT_MESSAGES } from './directorySyncLabels'

/**
 * Der Zugang, mit dem der Verzeichnislauf dieses Anbieters liest (#1817). Der Realm wird nie
 * eingetragen — er stammt aus der Issuer-Adresse des Anbieters. Das Geheimnis verlässt den Server
 * nie wieder; für einen erneuten Test genügt es, das Feld leer zu lassen.
 */
export default function DirectoryConnectorSection({
  provider,
  onChanged,
}: {
  provider: OidcProviderResponse
  onChanged: () => Promise<void>
}) {
  const connector = provider.directoryConnector
  const [baseUrl, setBaseUrl] = useState(connector?.baseUrl ?? '')
  const [clientId, setClientId] = useState(connector?.clientId ?? '')
  const [clientSecret, setClientSecret] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function run(action: () => Promise<void>, fallback: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (err) {
      setError(apiErrorMessage(err, DIRECTORY_SYNC_CONFLICT_MESSAGES, fallback))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Box>
      {connector ? (
        <KeyValueList
          sx={{ mb: 2 }}
          entries={[
            {
              label: 'Verzeichnis',
              value: connector.type === 'KEYCLOAK' ? 'Keycloak' : connector.type,
            },
            { label: 'Realm', value: connector.realm },
            { label: 'Dienstkonto', value: connector.clientId },
            { label: 'Adresse der Admin-API', value: connector.baseUrl },
          ]}
        />
      ) : (
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 2 }}>
          Noch kein Zugang hinterlegt. Ohne ihn meldet jeder Lauf das Verzeichnis als nicht
          erreichbar — und entzieht damit nichts.
        </Typography>
      )}

      {error && (
        <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Stack spacing={1.5}>
        <TextField
          size="small"
          fullWidth
          label="Dienstkonto (Client-ID)"
          value={clientId}
          onChange={(e) => setClientId(e.target.value)}
        />
        <TextField
          size="small"
          fullWidth
          type="password"
          label="Geheimnis des Dienstkontos"
          helperText={
            connector
              ? 'Leer lassen, um mit dem hinterlegten Geheimnis zu testen. Zum Speichern neu eingeben.'
              : 'Wird verschlüsselt gespeichert und nie wieder angezeigt.'
          }
          value={clientSecret}
          onChange={(e) => setClientSecret(e.target.value)}
        />
        <TextField
          size="small"
          fullWidth
          label="Adresse der Admin-API (optional)"
          helperText="Leer lassen, wenn OPAA sie unter der Adresse des Anbieters erreicht."
          value={baseUrl}
          onChange={(e) => setBaseUrl(e.target.value)}
        />
      </Stack>

      <Stack direction="row" spacing={1} sx={{ mt: 1.5, flexWrap: 'wrap' }}>
        <Button
          variant="outlined"
          size="small"
          disabled={busy || clientId.trim() === '' || clientSecret.trim() === ''}
          onClick={() =>
            void run(async () => {
              await saveDirectoryConnector(provider.id, {
                type: 'KEYCLOAK',
                baseUrl: baseUrl.trim() === '' ? null : baseUrl.trim(),
                clientId: clientId.trim(),
                clientSecret,
              })
              setClientSecret('')
              notify('Der Verzeichniszugang wurde gespeichert.', 'success')
              await onChanged()
            }, 'Der Zugang konnte nicht gespeichert werden.')
          }
        >
          Zugang speichern
        </Button>
        <Button
          size="small"
          disabled={busy || clientId.trim() === ''}
          onClick={() =>
            void run(async () => {
              const result = await testDirectoryConnector(provider.id, {
                type: 'KEYCLOAK',
                baseUrl: baseUrl.trim() === '' ? null : baseUrl.trim(),
                clientId: clientId.trim(),
                clientSecret: clientSecret.trim() === '' ? null : clientSecret,
              })
              notify(result.message, result.success ? 'success' : 'error')
            }, 'Der Verbindungstest ist fehlgeschlagen.')
          }
        >
          Verbindung testen
        </Button>
        {connector && (
          <Button
            size="small"
            color="error"
            disabled={busy}
            onClick={async () => {
              const confirmed = await confirmAction({
                question: 'Verzeichniszugang entfernen?',
                consequence:
                  'Der Abgleich bleibt eingeschaltet und meldet das Verzeichnis danach als nicht erreichbar — entzogen wird dadurch nichts.',
                confirmLabel: 'Entfernen',
                tone: 'danger',
              })
              if (!confirmed) return
              await run(async () => {
                await deleteDirectoryConnector(provider.id)
                await onChanged()
              }, 'Der Zugang konnte nicht entfernt werden.')
            }}
          >
            Zugang entfernen
          </Button>
        )}
      </Stack>
    </Box>
  )
}
