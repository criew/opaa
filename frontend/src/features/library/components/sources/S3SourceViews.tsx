import { s3SettingsFromLibrary } from '../../utils/s3Source'
import S3EventSection from '../S3EventSection'
import type { StoredLibrarySource } from './types'

export function S3Push({
  library,
  libraryId,
}: {
  library: StoredLibrarySource
  libraryId: string
}) {
  return (
    <S3EventSection
      libraryId={libraryId}
      tokenSet={library.pushSecretSet}
      scopes={s3SettingsFromLibrary(library)?.scopes ?? []}
    />
  )
}
