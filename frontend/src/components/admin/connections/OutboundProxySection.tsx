import { useCallback, useEffect, useState, type FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { OutboundProxyResponse } from '@/shared/api/types'
import { getOutboundProxy, updateOutboundProxy } from '../../../services/outboundProxyApi'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'
import { confirmAction } from '../../../stores/confirmStore'
import { notify } from '../../../stores/notificationStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import PageSection from '@/shared/ui/PageSection'

/** One entry per line; blank lines drop out. */
function entriesOf(text: string): string[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
}

function sameEntries(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((entry, index) => entry === b[index])
}

/**
 * The house-wide outbound proxy and its exclusion list. Every HTTP(S) connection of the backend
 * goes through the proxy unless its target is on the list; without a proxy, everything goes direct.
 */
export default function OutboundProxySection() {
  const [current, setCurrent] = useState<OutboundProxyResponse | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [proxyDraft, setProxyDraft] = useState('')
  const [noProxyDraft, setNoProxyDraft] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [saveError, setSaveError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const adopt = useCallback((stored: OutboundProxyResponse) => {
    setCurrent(stored)
    setProxyDraft(stored.proxyUrl ?? '')
    setNoProxyDraft(stored.noProxy.join('\n'))
  }, [])

  const load = useCallback(
    () =>
      getOutboundProxy()
        .then((loaded) => {
          adopt(loaded)
          setLoadError(null)
        })
        .catch((err: unknown) => {
          setLoadError(
            err instanceof Error ? err.message : 'Der Ausgangsproxy konnte nicht geladen werden.',
          )
        }),
    [adopt],
  )

  useEffect(() => {
    void load()
  }, [load])

  const proxyUrl = proxyDraft.trim() === '' ? null : proxyDraft.trim()
  const noProxy = entriesOf(noProxyDraft)
  const changed =
    current !== null &&
    (proxyUrl !== (current.proxyUrl ?? null) || !sameEntries(noProxy, current.noProxy))

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (busy || !changed) return
    const confirmed = await confirmAction({
      question:
        proxyUrl === null
          ? 'Ausgangsproxy entfernen und alle Verbindungen direkt aufbauen?'
          : `Ausgangsproxy ${proxyUrl} speichern?`,
      consequence:
        proxyUrl === null
          ? 'Alle ausgehenden Verbindungen gehen ab sofort direkt hinaus. Die Änderung steht im Revisionsprotokoll.'
          : 'Ab sofort gehen alle ausgehenden HTTP(S)-Verbindungen über diesen Proxy, außer zu den Zielen der Ausnahmeliste – auch die Anmeldung über einen Identitätsanbieter. Ein falscher Proxy kann die Anmeldung über den Anbieter lahmlegen; das lokale Konto der Systemverwaltung bleibt nutzbar. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Speichern',
      tone: 'caution',
    })
    if (!confirmed) return
    setBusy(true)
    setSaveError(null)
    setFieldErrors({})
    try {
      const saved = await updateOutboundProxy({ proxyUrl, noProxy })
      adopt(saved)
      notify(
        saved.proxyUrl
          ? `Ausgehende Verbindungen gehen jetzt über ${saved.proxyUrl}.`
          : 'Ausgehende Verbindungen gehen jetzt direkt hinaus.',
        'success',
      )
    } catch (err: unknown) {
      const perField = apiFieldErrors(err)
      setFieldErrors(Object.fromEntries(perField.map((e) => [e.field, e.message])))
      setSaveError(
        err instanceof Error ? err.message : 'Der Ausgangsproxy konnte nicht gespeichert werden.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <PageSection
      title="Ausgangsproxy"
      description="Über diesen Proxy baut OPAA alle ausgehenden HTTP(S)-Verbindungen auf - nicht nur zu Quellen, sondern auch zu Sprachmodellen und zur Anmeldung bei Anbietern und Identitätsanbietern. Ziele auf der Ausnahmeliste erreicht OPAA direkt."
    >
      {loadError ? (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => void load()}>
              Erneut laden
            </Button>
          }
        >
          {loadError}
        </Alert>
      ) : current === null ? (
        <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Ausgangsproxy wird geladen …
        </Typography>
      ) : (
        <Box component="form" onSubmit={(event) => void handleSubmit(event)} noValidate>
          <Typography variant="body2" sx={{ mb: 2 }} data-testid="outbound-proxy-current">
            {current.proxyUrl
              ? `Ausgehende Verbindungen gehen über ${current.proxyUrl}.`
              : 'Es ist kein Ausgangsproxy eingetragen; alle Verbindungen gehen direkt hinaus.'}
          </Typography>
          <Stack spacing={2} sx={{ maxWidth: 560 }}>
            <TextField
              label="Proxy-Adresse"
              size="small"
              value={proxyDraft}
              placeholder="http://proxy.example.org:3128"
              onChange={(event) => {
                setProxyDraft(event.target.value)
                setFieldErrors((errors) => ({ ...errors, proxyUrl: '' }))
              }}
              error={Boolean(fieldErrors.proxyUrl)}
              helperText={
                fieldErrors.proxyUrl ||
                'http://host:port oder host:port. Leer lassen, um alle Verbindungen direkt aufzubauen.'
              }
            />
            <TextField
              label="Ausnahmeliste"
              size="small"
              multiline
              minRows={3}
              value={noProxyDraft}
              placeholder={'intranet.example.org\n10.0.0.0/8'}
              onChange={(event) => {
                setNoProxyDraft(event.target.value)
                setFieldErrors((errors) => ({ ...errors, noProxy: '' }))
              }}
              error={Boolean(fieldErrors.noProxy)}
              helperText={
                fieldErrors.noProxy ||
                'Ein Eintrag je Zeile: ein Hostname (gilt auch für alle Subdomänen), eine IP-Adresse oder ein Netzbereich wie 10.0.0.0/8. Interne Ziele gehören hierher.'
              }
            />
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
              Die Ausnahmeliste gibt keine internen Adressen frei. Welche internen Ziele OPAA
              überhaupt erreichen darf, legt der Betrieb über
              OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST fest.
            </Typography>
            <Box>
              <BusyButton
                type="submit"
                variant="outlined"
                busy={busy}
                busyAnnouncement="Ausgangsproxy wird gespeichert"
                disabled={!changed && !busy}
              >
                Ausgangsproxy speichern
              </BusyButton>
            </Box>
          </Stack>
          {saveError && (
            <Alert severity="error" role="alert" sx={{ mt: 2 }}>
              {saveError}
            </Alert>
          )}
        </Box>
      )}
    </PageSection>
  )
}
