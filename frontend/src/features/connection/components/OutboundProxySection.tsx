import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import type { OutboundProxyResponse } from '@/shared/api/types'
import { getOutboundProxy, updateOutboundProxy } from '../api/outboundProxyApi'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import SettingsForm from '@/shared/ui/settings/SettingsForm'
import PageSection from '@/shared/ui/PageSection'
import { fontSize } from '@/shared/theme/tokens'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

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

  function discard() {
    if (current) adopt(current)
    setFieldErrors({})
    setSaveError(null)
  }

  async function handleSave() {
    if (busy || !changed) return
    const confirmed = await confirmAction({
      question:
        proxyUrl === null ? 'Ausgangsproxy entfernen?' : `Ausgangsproxy ${proxyUrl} speichern?`,
      consequence:
        proxyUrl === null
          ? 'Alle ausgehenden Verbindungen gehen ab sofort direkt hinaus. Die Änderung steht im Revisionsprotokoll.'
          : 'Ab sofort gehen alle ausgehenden HTTP(S)-Verbindungen über diesen Proxy, außer zu den Zielen der Ausnahmeliste – auch die Anmeldung über einen Identitätsanbieter. Ein falscher Proxy kann die Anmeldung über den Anbieter lahmlegen; das lokale Konto der Systemverwaltung bleibt nutzbar. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: proxyUrl === null ? 'Entfernen' : 'Speichern',
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
      description="Alle ausgehenden HTTP(S)-Verbindungen laufen über diesen Proxy, auch zu Sprachmodellen und Identitätsanbietern; Ziele der Ausnahmeliste erreicht OPAA direkt."
    >
      {loadError ? (
        <LoadError
          message="Der Ausgangsproxy konnte nicht geladen werden."
          detail={loadError}
          onRetry={() => void load()}
        />
      ) : current === null ? (
        <LoadingState shape="lines" count={2} />
      ) : (
        <SettingsForm
          onSave={handleSave}
          busy={busy}
          busyAnnouncement="Ausgangsproxy wird gespeichert"
          dirty={changed}
          onDiscard={discard}
          result={
            saveError && (
              <Alert severity="error" role="alert">
                {saveError}
              </Alert>
            )
          }
        >
          <Typography variant="body2" sx={{ mb: 2 }} data-testid="outbound-proxy-current">
            {current.proxyUrl
              ? `Ausgehende Verbindungen gehen über ${current.proxyUrl}.`
              : 'Es ist kein Ausgangsproxy eingetragen; alle Verbindungen gehen direkt hinaus.'}
          </Typography>
          <Stack spacing={2}>
            <FormField
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
            <FormField
              label="Ausnahmeliste"
              size="small"
              multiline
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
            <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
              Die Ausnahmeliste gibt keine internen Adressen frei. Welche internen Ziele OPAA
              überhaupt erreichen darf, legt der Betrieb über
              OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST fest.
            </Typography>
          </Stack>
        </SettingsForm>
      )}
    </PageSection>
  )
}
