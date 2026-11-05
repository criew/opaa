import { useCallback, useEffect, useState } from 'react'
import type { ServiceAccountDetailResponse, ServiceAccountResponse } from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { deactivateServiceAccount, getServiceAccount } from '../../api/serviceAccountApi'
import { DEACTIVATE_CONSEQUENCE } from './serviceAccountLabels'

const ERROR_PREFIX = 'Fehler: '

/**
 * While a form shows validation errors, the document title starts with „Fehler: “ (UI-Prüfliste
 * P29); the title in force before comes back once the errors are gone or the form closes.
 */
export function useErrorTitle(hasErrors: boolean): void {
  useEffect(() => {
    if (!hasErrors) return
    const before = document.title
    document.title = before.startsWith(ERROR_PREFIX) ? before : `${ERROR_PREFIX}${before}`
    return () => {
      document.title = before
    }
  }, [hasErrors])
}

/**
 * Loads one account with its tokens and libraries; `reload` after every write. A result belongs
 * to the id it was loaded for, so switching accounts never shows the previous one.
 */
export function useServiceAccountDetail(id: string | null) {
  const [loaded, setLoaded] = useState<{
    id: string
    detail: ServiceAccountDetailResponse | null
    error: string | null
  } | null>(null)

  const reload = useCallback(() => {
    if (!id) return Promise.resolve()
    return getServiceAccount(id)
      .then((detail) => setLoaded({ id, detail, error: null }))
      .catch((err: unknown) =>
        setLoaded({
          id,
          detail: null,
          error:
            err instanceof Error ? err.message : 'Das Dienstkonto konnte nicht geladen werden.',
        }),
      )
  }, [id])

  useEffect(() => {
    void reload()
  }, [reload])

  const current = loaded && loaded.id === id ? loaded : null
  return { detail: current?.detail ?? null, error: current?.error ?? null, reload }
}

export async function confirmDeactivate(
  account: ServiceAccountResponse,
  onDone: () => void,
): Promise<void> {
  const confirmed = await confirmAction({
    question: `Dienstkonto „${account.name}“ deaktivieren?`,
    consequence: DEACTIVATE_CONSEQUENCE,
    confirmLabel: 'Deaktivieren',
    tone: 'danger',
  })
  if (!confirmed) return
  try {
    await deactivateServiceAccount(account.id)
    notify(`„${account.name}“ ist deaktiviert; alle Tokens sind widerrufen.`, 'success')
    onDone()
  } catch (err) {
    notify(err instanceof Error ? err.message : 'Das Deaktivieren ist fehlgeschlagen.', 'error')
  }
}
