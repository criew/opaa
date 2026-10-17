import type { Ref } from 'react'
import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import type { SuccessionStateResponse } from '@/shared/api/types'
import { SuccessionStateNote } from '@/features/succession'
import {
  PRIVATE_LIBRARY_ERASING_NOTE,
  QUOTA_EXHAUSTED_LABEL,
  QUOTA_EXHAUSTED_REMEDY,
} from './privateLibrary'
import { formatIndexedAt } from './libraryDetailAccess'

interface LibraryHeaderNoteProps {
  succession: SuccessionStateResponse | null | undefined
  erasureRequestedAt: string | null
  /** Takes the focus once an erasure was only marked. */
  erasureNoticeRef: Ref<HTMLDivElement>
  quotaExhausted: boolean
}

/** The notes in the head of a library: open succession, marked erasure, exhausted quota. */
export default function LibraryHeaderNote({
  succession,
  erasureRequestedAt,
  erasureNoticeRef,
  quotaExhausted,
}: LibraryHeaderNoteProps) {
  return (
    <>
      <SuccessionStateNote succession={succession} />
      {erasureRequestedAt && (
        <Alert
          ref={erasureNoticeRef}
          tabIndex={-1}
          role="status"
          severity="error"
          icon={false}
          sx={{ mt: 1.5 }}
          data-testid="library-erasure-notice"
        >
          <AlertTitle>
            Wird gelöscht – vorgemerkt am {formatIndexedAt(erasureRequestedAt)}
          </AlertTitle>
          {PRIVATE_LIBRARY_ERASING_NOTE}
        </Alert>
      )}
      {quotaExhausted && (
        <Alert severity="warning" sx={{ mt: 1.5 }} data-testid="private-quota-exhausted-notice">
          <AlertTitle>Letzter Lauf {QUOTA_EXHAUSTED_LABEL}</AlertTitle>
          {QUOTA_EXHAUSTED_REMEDY}
        </Alert>
      )}
    </>
  )
}
