import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import AttachFileIcon from '@mui/icons-material/AttachFile'
import type { ChatAttachment } from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import SectionHead from '@/shared/ui/SectionHead'
import { formatFileSize } from '@/shared/utils/labels'
import { useLibraryStore } from '@/features/library'
import {
  copyChatAttachmentToLibrary,
  deleteChatAttachment,
  downloadChatAttachment,
  listMyChatAttachments,
} from '../api/chatAttachmentApi'
import { fontSize, iconSize } from '@/shared/theme/tokens'
import { formatDate } from '@/shared/utils/formatDate'

const UPLOAD_ROLES = new Set(['EDITOR', 'MANAGER', 'OWNER'])

function formatDay(iso: string): string {
  return formatDate(iso)
}

/** Where the attachment lives and how long - the line under its name. */
function attachmentContext(attachment: ChatAttachment): string {
  const parts = [
    formatFileSize(attachment.sizeBytes),
    `hochgeladen am ${formatDay(attachment.createdAt)}`,
  ]
  if (attachment.chatId) {
    parts.push(`im Chat „${attachment.chatTitle?.trim() || 'Ohne Titel'}“ · gelöscht mit dem Chat`)
  } else if (attachment.deletionDueAt) {
    parts.push(`noch nicht gesendet · wird am ${formatDay(attachment.deletionDueAt)} gelöscht`)
  }
  if (attachment.status === 'FAILED') parts.push(attachment.failureReason ?? 'nicht nutzbar')
  if (attachment.status === 'PROCESSING') parts.push('wird gelesen')
  return parts.join(' · ')
}

function CopyToLibraryDialog({
  attachment,
  onClose,
}: {
  attachment: ChatAttachment | null
  onClose: () => void
}) {
  const libraries = useLibraryStore((s) => s.libraries)
  const loadLibraries = useLibraryStore((s) => s.loadLibraries)
  const [libraryId, setLibraryId] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const targets = useMemo(
    () =>
      libraries.filter(
        (library) => library.sourceType === 'UPLOAD' && UPLOAD_ROLES.has(library.myRole),
      ),
    [libraries],
  )

  useEffect(() => {
    if (attachment) void loadLibraries()
  }, [attachment, loadLibraries])

  const close = () => {
    setLibraryId('')
    setError(null)
    onClose()
  }

  const copy = async () => {
    if (!attachment || !libraryId) return
    setBusy(true)
    setError(null)
    try {
      await copyChatAttachmentToLibrary(attachment.id, libraryId)
      const name = targets.find((library) => library.id === libraryId)?.name ?? 'die Bibliothek'
      notify(`„${attachment.fileName}“ in ${name} übernommen`, 'success')
      close()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Übernehmen fehlgeschlagen')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={attachment !== null} onClose={close} fullWidth maxWidth="xs">
      <DialogTitle>In Bibliothek übernehmen</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mb: 2 }}>
          Eine Kopie von „{attachment?.fileName}“ wird in der Bibliothek indexiert und folgt danach
          deren Rechten und Fristen. Der Anhang im Chat bleibt unverändert.
        </Typography>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        {targets.length === 0 ? (
          <Typography sx={{ fontSize: fontSize.text }}>
            Sie können in keine Upload-Bibliothek Dokumente hochladen.
          </Typography>
        ) : (
          <FormField
            select
            fullWidth
            size="small"
            label="Bibliothek"
            value={libraryId}
            onChange={(e) => setLibraryId(e.target.value)}
          >
            {targets.map((library) => (
              <MenuItem key={library.id} value={library.id}>
                {library.name}
              </MenuItem>
            ))}
          </FormField>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={close}>Abbrechen</Button>
        <Button variant="contained" disabled={!libraryId || busy} onClick={() => void copy()}>
          Übernehmen
        </Button>
      </DialogActions>
    </Dialog>
  )
}

/**
 * The files the person attached to questions, over all spaces: a view of the chat attachments,
 * which live and die with their chat. From here one is saved, copied into a library or deleted.
 */
export default function MyChatAttachmentsSection() {
  const [attachments, setAttachments] = useState<ChatAttachment[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [copying, setCopying] = useState<ChatAttachment | null>(null)

  useEffect(() => {
    let active = true
    listMyChatAttachments().then(
      (loaded) => {
        if (active) setAttachments(loaded)
      },
      (err: unknown) => {
        if (active)
          setError(err instanceof Error ? err.message : 'Die Anhänge konnten nicht geladen werden.')
      },
    )
    return () => {
      active = false
    }
  }, [])

  const remove = async (attachment: ChatAttachment) => {
    const confirmed = await confirmAction({
      question: `„${attachment.fileName}“ löschen?`,
      consequence: attachment.chatId
        ? 'Die Datei und ihr Text werden gelöscht; der Chat nennt sie danach nicht mehr als Quelle.'
        : 'Die Datei und ihr Text werden gelöscht.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    try {
      await deleteChatAttachment(attachment.id)
      setAttachments((current) => current?.filter((entry) => entry.id !== attachment.id) ?? null)
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Löschen fehlgeschlagen', 'error')
    }
  }

  const download = (attachment: ChatAttachment) =>
    void downloadChatAttachment(attachment).catch((err: unknown) =>
      notify(err instanceof Error ? err.message : 'Herunterladen fehlgeschlagen', 'error'),
    )

  return (
    <Box component="section" aria-labelledby="my-chat-attachments-head">
      <SectionHead id="my-chat-attachments-head">Meine Uploads</SectionHead>
      <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mb: 2 }}>
        Dateien, die Sie an Fragen im Chat angehängt haben. Ein Anhang gehört zu seinem Chat und
        wird mit ihm gelöscht; nie gesendete Anhänge werden nach Ablauf ihrer Frist gelöscht.
      </Typography>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {attachments !== null && attachments.length === 0 && (
        <Typography sx={{ fontSize: fontSize.text }}>Sie haben keine Anhänge.</Typography>
      )}
      {attachments !== null && attachments.length > 0 && (
        <Stack component="ul" spacing={1} sx={{ p: 0, m: 0, listStyle: 'none' }}>
          {attachments.map((attachment) => (
            <Box
              component="li"
              key={attachment.id}
              sx={{
                display: 'flex',
                flexWrap: 'wrap',
                alignItems: 'center',
                gap: 1.5,
                py: 1.25,
                borderBottom: 1,
                borderColor: 'divider',
              }}
            >
              <AttachFileIcon aria-hidden sx={{ fontSize: iconSize.md, color: 'text.secondary' }} />
              <Box sx={{ flex: '1 1 240px', minWidth: 0 }}>
                <Typography
                  sx={{ fontSize: fontSize.text, fontWeight: 600, overflowWrap: 'anywhere' }}
                >
                  {attachment.fileName}
                </Typography>
                <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
                  {attachmentContext(attachment)}
                </Typography>
              </Box>
              <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap' }}>
                <Button size="small" onClick={() => download(attachment)}>
                  Herunterladen
                </Button>
                <Button
                  size="small"
                  disabled={attachment.status !== 'READY'}
                  onClick={() => setCopying(attachment)}
                >
                  In Bibliothek übernehmen
                </Button>
                <Button size="small" color="error" onClick={() => void remove(attachment)}>
                  Löschen
                </Button>
              </Stack>
            </Box>
          ))}
        </Stack>
      )}
      <CopyToLibraryDialog attachment={copying} onClose={() => setCopying(null)} />
    </Box>
  )
}
