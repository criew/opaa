import { useState } from 'react'
import type { OidcProviderResponse } from '@/shared/api/types'
import RowMenu from '@/shared/ui/RowMenu'
import { apiErrorMessage } from '@/shared/api/apiErrorDetails'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'
import { PROVIDER_CONFLICT_MESSAGES } from './oidcProviderConflicts'
import ProviderShutdownDialog, { type ProviderShutdownAction } from './ProviderShutdownDialog'

export const DEFAULT_CONSEQUENCE =
  'Der Standardanbieter ist der einzige, der weder deaktiviert noch gelöscht werden kann; die ' +
  'Erstadministrator-Regel und der Verzeichnisabgleich gelten nur für seine Konten.'

/** Why two actions are missing on the default provider — as text, not as a tooltip. */
export const DEFAULT_PROVIDER_HINT =
  'Der Standardanbieter lässt sich weder deaktivieren noch löschen. Machen Sie zuerst einen ' +
  'anderen aktivierten Anbieter zum Standard – als letzter Anbieter ist auch er beides.'

interface ProviderRowMenuProps {
  provider: OidcProviderResponse
  /** The one enabled provider left: its confirmation *is* the backend's acknowledgement. */
  isLastEnabled: boolean
  canDisable: boolean
  canDelete: boolean
  onEdit: (provider: OidcProviderResponse) => void
}

/**
 * The row menu of an identity provider.
 *
 * Two rules of the area live here. First: the default provider can neither be
 * deactivated nor deleted as long as another one exists — the entries are then **disabled and
 * name their reason** instead of simply being missing. A missing action raises the question
 * whether it was ever there; a disabled one answers it.
 *
 * Second: for the last enabled provider **the confirmation is the acknowledgement** the
 * backend requires (ADR-0025). The additional paragraph and the flag therefore belong together —
 * the flag goes out only after the paragraph was read. The same applies to the consequences for
 * connected accounts ({@link ProviderShutdownDialog}).
 */
export default function ProviderRowMenu({
  provider,
  isLastEnabled,
  canDisable,
  canDelete,
  onEdit,
}: ProviderRowMenuProps) {
  const setProviderEnabled = useOidcProviderStore((s) => s.setProviderEnabled)
  const makeProviderDefault = useOidcProviderStore((s) => s.makeProviderDefault)
  const [busy, setBusy] = useState(false)
  const [shutdown, setShutdown] = useState<ProviderShutdownAction | null>(null)

  async function run(action: () => Promise<unknown>, erfolg: string, fallback: string) {
    setBusy(true)
    try {
      await action()
      notify(erfolg, 'success')
    } catch (err) {
      notify(apiErrorMessage(err, PROVIDER_CONFLICT_MESSAGES, fallback), 'error')
    } finally {
      setBusy(false)
    }
  }

  async function toggleEnabled() {
    if (provider.enabled) {
      setShutdown('disable')
      return
    }
    // Switching on does not ask - it takes nobody's login away.
    await run(
      () => setProviderEnabled(provider.id, true),
      `„${provider.displayName}“ wurde aktiviert.`,
      'Änderung fehlgeschlagen',
    )
  }

  async function makeDefault() {
    if (
      !(await confirmAction({
        question: `„${provider.displayName}“ zum Standardanbieter machen?`,
        consequence: DEFAULT_CONSEQUENCE,
        confirmLabel: 'Zum Standard machen',
        tone: 'caution',
      }))
    ) {
      return
    }
    await run(
      () => makeProviderDefault(provider.id),
      `„${provider.displayName}“ ist jetzt der Standardanbieter.`,
      'Änderung fehlgeschlagen',
    )
  }

  function remove() {
    setShutdown('delete')
  }

  return (
    <>
      <RowMenu
        name={provider.displayName}
        disabled={busy}
        items={[
          { label: 'Bearbeiten', onClick: () => onEdit(provider) },
          {
            label: provider.enabled ? 'Deaktivieren' : 'Aktivieren',
            onClick: () => void toggleEnabled(),
            disabled: !canDisable,
            disabledReason: DEFAULT_PROVIDER_HINT,
          },
          !provider.isDefault &&
            provider.enabled && {
              label: 'Zum Standard machen',
              onClick: () => void makeDefault(),
            },
          {
            label: 'Arbeitsliste der Gruppen',
            to: `/admin/identity-providers/${provider.id}/groups`,
          },
          {
            label: 'Löschen',
            onClick: remove,
            danger: true,
            disabled: !canDelete,
            disabledReason: DEFAULT_PROVIDER_HINT,
          },
        ]}
      />
      {shutdown && (
        <ProviderShutdownDialog
          provider={provider}
          action={shutdown}
          isLastEnabled={isLastEnabled}
          onClose={() => setShutdown(null)}
        />
      )}
    </>
  )
}
