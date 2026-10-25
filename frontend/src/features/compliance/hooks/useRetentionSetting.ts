import { useCallback, useEffect, useState } from 'react'
import { apiErrorStatus } from '@/shared/api/apiErrorDetails'
import { notify } from '@/shared/stores/notificationStore'

interface RetentionSettingState<T> {
  current: T | null
  loadError: string | null
  reload: () => void
  /** Sends `update`; resolves to whether the server took it, and tells the outcome. */
  save: (update: Promise<T>, success: (saved: T) => string) => Promise<boolean>
}

/** What a section says when its setting cannot be loaded or saved. */
interface RetentionMessages {
  loadFailed: string
  saveFailed: string
}

/** Loading and saving of one retention setting, with the messages every section shares. */
export function useRetentionSetting<T>(
  load: () => Promise<T>,
  messages: RetentionMessages,
): RetentionSettingState<T> {
  const { loadFailed, saveFailed } = messages
  const [current, setCurrent] = useState<T | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const reload = useCallback(() => {
    load()
      .then((loaded) => {
        setCurrent(loaded)
        setLoadError(null)
      })
      .catch(() => setLoadError(loadFailed))
  }, [load, loadFailed])

  useEffect(() => {
    reload()
  }, [reload])

  const save = useCallback(
    async (update: Promise<T>, success: (saved: T) => string) => {
      try {
        const saved = await update
        setCurrent(saved)
        notify(success(saved), 'success')
        return true
      } catch (err: unknown) {
        notify(
          apiErrorStatus(err) === 400 && err instanceof Error
            ? `Nicht gespeichert: ${err.message}`
            : saveFailed,
          'error',
        )
        return false
      }
    },
    [saveFailed],
  )

  return { current, loadError, reload, save }
}
