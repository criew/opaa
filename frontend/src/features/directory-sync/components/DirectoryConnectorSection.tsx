import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import type { OidcProviderResponse } from '@/shared/api/types'
import {
  deleteDirectoryConnector,
  saveDirectoryConnector,
  testDirectoryConnector,
} from '../api/directorySyncApi'
import { apiErrorMessage } from '@/shared/api/apiErrorDetails'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import KeyValueList from '@/shared/ui/KeyValueList'
import { DIRECTORY_SYNC_CONFLICT_MESSAGES } from './directorySyncLabels'
import { fontSize } from '@/shared/theme/tokens'
import ConnectionTestButton from '@/shared/ui/settings/ConnectionTestButton'
import SettingsForm from '@/shared/ui/settings/SettingsForm'

/**
 * The access with which this provider's directory run reads. The realm is never
 * entered — it comes from the provider's issuer address. The secret never leaves the server
 * again; for a renewed test it is enough to leave the field empty.
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
  const [busy, setBusy] = useState<'save' | 'test' | 'remove' | null>(null)
  const [missing, setMissing] = useState<{ clientId?: boolean; clientSecret?: boolean }>({})
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)

  // what the fields held when last saved: an empty address stays empty, though the server derives one
  const [saved, setSaved] = useState({ clientId, baseUrl })

  const dirty =
    clientId.trim() !== saved.clientId.trim() ||
    baseUrl.trim() !== saved.baseUrl.trim() ||
    clientSecret !== ''

  function discard() {
    setClientId(saved.clientId)
    setBaseUrl(saved.baseUrl)
    setClientSecret('')
    setMissing({})
    setError(null)
    setTestResult(null)
  }

  async function run(
    kind: 'save' | 'test' | 'remove',
    action: () => Promise<void>,
    fallback: string,
  ) {
    setBusy(kind)
    setError(null)
    try {
      await action()
    } catch (err) {
      setError(apiErrorMessage(err, DIRECTORY_SYNC_CONFLICT_MESSAGES, fallback))
    } finally {
      setBusy(null)
    }
  }

  /** What an action needs is told at its field; the secret is needed only to save. */
  function complete(secretRequired: boolean): boolean {
    const next = {
      clientId: clientId.trim() === '',
      clientSecret: secretRequired && clientSecret.trim() === '',
    }
    setMissing(next)
    return !next.clientId && !next.clientSecret
  }

  async function handleSave() {
    if (busy || !complete(true)) return
    await run(
      'save',
      async () => {
        await saveDirectoryConnector(provider.id, {
          type: 'KEYCLOAK',
          baseUrl: baseUrl.trim() === '' ? null : baseUrl.trim(),
          clientId: clientId.trim(),
          clientSecret,
        })
        setClientSecret('')
        setSaved({ clientId, baseUrl })
        notify('Der Verzeichniszugang wurde gespeichert.', 'success')
        await onChanged()
      },
      'Der Zugang konnte nicht gespeichert werden.',
    )
  }

  async function handleTest() {
    if (busy || !complete(false)) return
    setTestResult(null)
    await run(
      'test',
      async () => {
        setTestResult(
          await testDirectoryConnector(provider.id, {
            type: 'KEYCLOAK',
            baseUrl: baseUrl.trim() === '' ? null : baseUrl.trim(),
            clientId: clientId.trim(),
            clientSecret: clientSecret.trim() === '' ? null : clientSecret,
          }),
        )
      },
      'Der Verbindungstest ist fehlgeschlagen.',
    )
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
        <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mb: 2 }}>
          Noch kein Zugang hinterlegt. Ohne ihn meldet jeder Lauf das Verzeichnis als nicht
          erreichbar — und entzieht damit nichts.
        </Typography>
      )}

      <SettingsForm
        aria-label="Verzeichniszugang"
        onSave={handleSave}
        busy={busy === 'save'}
        busyAnnouncement="Zugang wird gespeichert"
        dirty={dirty}
        onDiscard={discard}
        secondaryAction={
          <ConnectionTestButton busy={busy === 'test'} onTest={() => void handleTest()} />
        }
        result={
          (testResult || error) && (
            <Stack spacing={2}>
              {testResult && (
                <Alert
                  severity={testResult.success ? 'success' : 'error'}
                  role={testResult.success ? 'status' : 'alert'}
                >
                  {testResult.message}
                </Alert>
              )}
              {error && (
                <Alert severity="error" role="alert" onClose={() => setError(null)}>
                  {error}
                </Alert>
              )}
            </Stack>
          )
        }
      >
        <Stack spacing={2}>
          <FormField
            size="small"
            fullWidth
            label="Dienstkonto (Client-ID)"
            value={clientId}
            error={Boolean(missing.clientId)}
            helperText={missing.clientId ? 'Dienstkonto fehlt' : undefined}
            onChange={(e) => {
              setClientId(e.target.value)
              setMissing((current) => ({ ...current, clientId: false }))
            }}
          />
          <FormField
            size="small"
            fullWidth
            type="password"
            label="Geheimnis des Dienstkontos"
            error={Boolean(missing.clientSecret)}
            helperText={
              missing.clientSecret
                ? 'Geheimnis fehlt'
                : connector
                  ? 'Leer lassen, um mit dem hinterlegten Geheimnis zu testen. Zum Speichern neu eingeben.'
                  : 'Wird verschlüsselt gespeichert und nie wieder angezeigt.'
            }
            value={clientSecret}
            onChange={(e) => {
              setClientSecret(e.target.value)
              setMissing((current) => ({ ...current, clientSecret: false }))
            }}
          />
          <FormField
            size="small"
            fullWidth
            label="Adresse der Admin-API"
            optional
            helperText="Leer lassen, wenn OPAA sie unter der Adresse des Anbieters erreicht."
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
          />
        </Stack>
      </SettingsForm>

      <Box sx={{ mt: 2 }}>
        {connector && (
          <Button
            size="small"
            color="error"
            disabled={busy !== null}
            onClick={async () => {
              const confirmed = await confirmAction({
                question: 'Verzeichniszugang entfernen?',
                consequence:
                  'Der Abgleich bleibt eingeschaltet und meldet das Verzeichnis danach als nicht erreichbar — entzogen wird dadurch nichts.',
                confirmLabel: 'Entfernen',
                tone: 'danger',
              })
              if (!confirmed) return
              await run(
                'remove',
                async () => {
                  await deleteDirectoryConnector(provider.id)
                  await onChanged()
                },
                'Der Zugang konnte nicht entfernt werden.',
              )
            }}
          >
            Zugang entfernen
          </Button>
        )}
      </Box>
    </Box>
  )
}
