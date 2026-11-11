import LoadError, { type LoadErrorMessage } from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

interface RetentionLoadStateProps {
  loadError: LoadErrorMessage | null
  onRetry: () => void
}

/** What a retention section shows until its setting is loaded: an error with retry, or a note. */
export default function RetentionLoadState({ loadError, onRetry }: RetentionLoadStateProps) {
  return loadError ? (
    <LoadError message={loadError} onRetry={onRetry} />
  ) : (
    <LoadingState shape="lines" count={2} />
  )
}
