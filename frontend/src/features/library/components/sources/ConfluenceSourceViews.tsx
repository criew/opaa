import ConfluenceWebhookSection from '../ConfluenceWebhookSection'
import type { StoredLibrarySource } from './types'

export function ConfluencePush({
  library,
  libraryId,
}: {
  library: StoredLibrarySource
  libraryId: string
}) {
  return <ConfluenceWebhookSection libraryId={libraryId} secretSet={library.pushSecretSet} />
}
