import { useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { useLibraryStore } from '../../stores/libraryStore'
import { sourceRegistration } from '../sources/registry'
import {
  connectionFields,
  payloadUnder,
  sourceConnectionOfRef,
  withConnection,
  withoutFixed,
} from '../sources/sourceConnection'
import type { SourceFormContext } from '../sources/types'
import type { EditableLibrarySource } from '../EditLibrarySourceDialog'
import CheckResult from './CheckResult'
import ConnectionPage from './ConnectionPage'
import ErrorSummary, { type SummaryError } from './ErrorSummary'
import ScopePicker from './ScopePicker'
import { checkConnection, checkRights, useCheck } from './connectionCheck'
import { checkSummary, fieldId, stageErrors } from './stepErrors'
import type { CheckOutcome, FieldErrors, StepContext } from './types'

interface EditSectionDialogProps {
  open: boolean
  onClose: () => void
  libraryId: string
  library: EditableLibrarySource
  /** The one thing this dialog changes. */
  section: 'connection' | 'scope'
}

const ID = 'edit-source'

/**
 * „Ändern“ of one section of the Reiter „Quelle“ - the connection or the scope, never both:
 * the same page as in the wizard, filled from the library. Stored credentials show as „Gesetzt“
 * with „Ersetzen“. „Speichern“ checks first; a failed check keeps the dialog open with the result
 * and offers to save anyway. Closing asks only when something was changed.
 */
export default function EditSectionDialog({
  open,
  onClose,
  libraryId,
  library,
  section,
}: EditSectionDialogProps) {
  const theme = useTheme()
  const narrow = useMediaQuery(theme.breakpoints.down('sm'))
  const configuration = sourceRegistration(library.sourceType)?.configuration ?? null
  const stepwise = configuration?.stepwise ?? null
  const updateExistingLibrary = useLibraryStore((s) => s.updateExistingLibrary)
  const profile = library.connectionProfile ?? null
  const connection = (profile && sourceConnectionOfRef(profile)) ?? undefined
  const [replacing, setReplacing] = useState(false)
  const formContext: SourceFormContext = {
    mode: 'edit',
    sourceType: library.sourceType,
    idPrefix: ID,
    libraryId,
    credentialsStored: Boolean(library.sourceCredentialsSet),
    originalSourceUrl: library.sourceUrl,
    connection,
    privateLibrary: library.privateLibrary,
  }
  const ctx: StepContext = {
    mode: 'edit',
    sourceType: library.sourceType,
    credentialsStored: Boolean(library.sourceCredentialsSet),
    replacingCredentials: replacing,
    fields: connectionFields(formContext),
    formContext,
  }
  const [initial] = useState<unknown>(() => configuration?.fromLibrary(library))
  const [stored, setStored] = useState<unknown>(initial)
  const values = withConnection(stored, connection)
  const dirty = JSON.stringify(stored) !== JSON.stringify(initial)
  const [attempted, setAttempted] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const summaryRef = useRef<HTMLDivElement>(null)

  const specs = stepwise ? stepwise.fields(values as never, ctx) : []
  const token = JSON.stringify({
    section,
    replacing,
    values:
      section === 'connection'
        ? specs.map((s) => (values as Record<string, unknown>)?.[s.key])
        : null,
    selection:
      section === 'scope' && stepwise?.scope
        ? stepwise.scope.selection(values as never).map((n) => n.id)
        : null,
  })
  const check = useCheck(token)
  const problems: FieldErrors = !stepwise
    ? {}
    : section === 'connection'
      ? stepwise.connectionErrors(values as never, ctx)
      : (stepwise.scope?.errors(values as never, ctx) ?? {})

  function change(patch: object) {
    const adjusted = stepwise?.adjust ? stepwise.adjust(patch as never, values as never) : patch
    setStored((prev: unknown) => ({
      ...(withConnection(prev, connection) as object),
      ...withoutFixed(adjusted as object, connection),
    }))
    setSaveError(null)
  }

  async function runCheck(): Promise<CheckOutcome | null> {
    if (!configuration || !stepwise) return null
    const outcome = await check.run(() =>
      section === 'connection'
        ? checkConnection(configuration, stepwise, values, ctx)
        : checkRights(configuration, stepwise, values, ctx),
    )
    if (outcome.patch) change(outcome.patch)
    return outcome
  }

  async function save(force: boolean) {
    if (!configuration || saving) return
    if (Object.keys(problems).length > 0) {
      setAttempted(true)
      summaryRef.current?.focus()
      return
    }
    if (!force) {
      const outcome = check.outcome ?? (await runCheck())
      if (!outcome || !outcome.ok) {
        setAttempted(true)
        return
      }
    }
    setSaving(true)
    try {
      await updateExistingLibrary(libraryId, {
        name: library.name,
        description: library.description ?? undefined,
        ...payloadUnder(configuration.toPayload(values), connectionFields(formContext)),
      })
      notify(
        section === 'connection' ? 'Verbindung gespeichert.' : 'Umfang gespeichert.',
        'success',
      )
      onClose()
    } catch (err) {
      setSaveError(err instanceof Error ? err.message : 'Die Änderung ließ sich nicht speichern.')
    } finally {
      setSaving(false)
    }
  }

  async function requestClose() {
    if (saving) return
    if (dirty) {
      const discard = await confirmAction({
        question: 'Änderungen verwerfen?',
        consequence: 'Ihre Eingaben in diesem Dialog gehen verloren.',
        confirmLabel: 'Verwerfen',
        tone: 'neutral',
      })
      if (!discard) return
    }
    onClose()
  }

  const outcome = check.outcome
  const shownErrors: FieldErrors = {
    ...(section === 'connection' && stepwise ? stageErrors(stepwise, specs, outcome) : {}),
    ...(attempted ? problems : {}),
  }
  const summary: SummaryError[] = attempted
    ? [
        ...Object.entries(problems).map(([key, message]) => ({
          message: message as string,
          targetId: key === 'scope' ? `${ID}-field-scope` : fieldId(ID, key),
        })),
        ...(Object.keys(problems).length === 0 && outcome && !outcome.ok
          ? [{ message: checkSummary(outcome) }]
          : []),
      ]
    : []
  const title = section === 'connection' ? 'Verbindung ändern' : 'Umfang ändern'

  return (
    <Dialog
      open={open}
      onClose={() => void requestClose()}
      maxWidth={section === 'scope' ? 'md' : 'sm'}
      fullWidth
      fullScreen={narrow}
      aria-labelledby={`${ID}-title`}
    >
      <DialogTitle id={`${ID}-title`}>{title}</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 2 }}>
          Die Änderung wirkt ab dem nächsten Lauf.
        </Typography>
        <ErrorSummary ref={summaryRef} errors={summary} />
        {saveError && (
          <Typography role="alert" sx={{ color: 'error.main', fontSize: 14, mb: 2 }}>
            {saveError}
          </Typography>
        )}
        {stepwise && section === 'connection' && (
          <ConnectionPage
            stepwise={stepwise}
            specs={specs}
            values={values}
            onChange={change}
            ctx={ctx}
            idPrefix={ID}
            errors={shownErrors}
            onLeave={() => {}}
            outcome={outcome}
            checking={check.running}
            onCheck={() => void runCheck()}
            onReplacingChange={setReplacing}
          />
        )}
        {stepwise?.scope && section === 'scope' && (
          <Box>
            <ScopePicker
              scope={stepwise.scope}
              values={values as never}
              onChange={change}
              ctx={ctx}
              idPrefix={ID}
              error={attempted ? problems.scope : undefined}
              failedEntries={outcome?.failedEntries}
            />
            {outcome && (
              <Box role="status" aria-live="polite" sx={{ mt: 2 }}>
                <CheckResult outcome={outcome} />
              </Box>
            )}
          </Box>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2, flexWrap: 'wrap', gap: 1 }}>
        {outcome && !outcome.ok && (
          <Button variant="text" onClick={() => void save(true)} sx={{ mr: 'auto' }}>
            Trotzdem speichern
          </Button>
        )}
        <Button variant="text" onClick={() => void requestClose()}>
          Abbrechen
        </Button>
        <BusyButton
          variant="contained"
          busy={saving || check.running}
          busyAnnouncement="Wird geprüft und gespeichert"
          onClick={() => void save(false)}
        >
          {check.running ? 'Wird geprüft …' : saving ? 'Wird gespeichert …' : 'Speichern'}
        </BusyButton>
      </DialogActions>
    </Dialog>
  )
}
