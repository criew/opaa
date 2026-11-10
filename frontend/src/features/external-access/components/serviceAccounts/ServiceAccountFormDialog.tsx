import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import FormLabel from '@mui/material/FormLabel'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import { UserPicker } from '@/features/principal'
import type { ServiceAccountResponse, UserSummary } from '@/shared/api/types'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'
import { getExternalAccessSettings } from '../../api/externalAccessApi'
import { createServiceAccount, updateServiceAccount } from '../../api/serviceAccountApi'
import { useErrorTitle } from './serviceAccountHooks'
import { fontSize } from '@/shared/theme/tokens'
import FormDialog from '@/shared/ui/dialog/FormDialog'

type Field = 'name' | 'responsibleUserId' | 'rateLimitPerHour'

/** The fields that show a backend message at themselves. */
const SHOWN_FIELDS: readonly Field[] = ['name', 'responsibleUserId', 'rateLimitPerHour']

interface ServiceAccountFormDialogProps {
  open: boolean
  /** The account to change; absent for a new one. */
  account?: ServiceAccountResponse | null
  onClose: () => void
  onSaved: (saved: ServiceAccountResponse) => void
  /** Label of the primary button for a new account; „Weiter“ where a step follows. */
  createLabel?: string
}

function responsibleOf(account: ServiceAccountResponse | null | undefined): UserSummary | null {
  if (!account?.responsibleUserId || account.responsiblePersonMissing) return null
  return {
    id: account.responsibleUserId,
    displayName: account.responsibleDisplayName ?? undefined,
  } as UserSummary
}

/**
 * Create or change a service account: name, description, the responsible person and the quota per
 * hour. Validation runs on submit; the first field in error takes the focus and the document title
 * starts with „Fehler: “.
 */
export default function ServiceAccountFormDialog(props: ServiceAccountFormDialogProps) {
  // a fresh form per opening: its fields start from the account, nothing carries over
  if (!props.open) return null
  return <AccountForm key={props.account?.id ?? 'new'} {...props} />
}

function AccountForm({
  open,
  account,
  onClose,
  onSaved,
  createLabel = 'Anlegen',
}: ServiceAccountFormDialogProps) {
  const [name, setName] = useState(account?.name ?? '')
  const [description, setDescription] = useState(account?.description ?? '')
  const [responsible, setResponsible] = useState<UserSummary | null>(responsibleOf(account))
  const [rateLimit, setRateLimit] = useState(
    account?.rateLimitPerHour != null ? String(account.rateLimitPerHour) : '',
  )
  const [channelDefault, setChannelDefault] = useState<number | null>(null)
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const nameRef = useRef<HTMLInputElement>(null)
  const personRef = useRef<HTMLInputElement>(null)
  const rateRef = useRef<HTMLInputElement>(null)
  useErrorTitle(Object.keys(errors).length > 0)

  useEffect(() => {
    void getExternalAccessSettings()
      .then((settings) => setChannelDefault(settings.tokenRateLimitPerHour))
      .catch(() => setChannelDefault(null))
  }, [])

  function focusFirst(found: Partial<Record<Field, string>>) {
    if (found.name) nameRef.current?.focus()
    else if (found.responsibleUserId) personRef.current?.focus()
    else if (found.rateLimitPerHour) rateRef.current?.focus()
  }

  async function submit() {
    const found: Partial<Record<Field, string>> = {}
    if (!name.trim()) found.name = 'Bitte einen Namen angeben.'
    if (!responsible) found.responsibleUserId = 'Bitte eine Person auswählen.'
    const limit = rateLimit.trim() === '' ? null : Number(rateLimit)
    if (limit !== null && (!Number.isInteger(limit) || limit < 1 || limit > 10000)) {
      found.rateLimitPerHour = 'Eine ganze Zahl von 1 bis 10.000.'
    }
    setErrors(found)
    if (Object.keys(found).length > 0) {
      focusFirst(found)
      return
    }
    setSaving(true)
    setFormError(null)
    const request = {
      name: name.trim(),
      description: description.trim() || null,
      responsibleUserId: responsible!.id,
      rateLimitPerHour: limit,
    }
    try {
      const saved = account
        ? await updateServiceAccount(account.id, request)
        : await createServiceAccount(request)
      onSaved(saved)
    } catch (err) {
      const fieldErrors = apiFieldErrors(err)
      if (fieldErrors.length > 0) {
        const mapped: Partial<Record<Field, string>> = {}
        const unshown: string[] = []
        fieldErrors.forEach((entry) => {
          if (SHOWN_FIELDS.includes(entry.field as Field))
            mapped[entry.field as Field] = entry.message
          else unshown.push(entry.message)
        })
        setErrors(mapped)
        // a field without an error line of its own (the description, say) speaks above the form
        setFormError(unshown.length > 0 ? unshown.join(' ') : null)
        focusFirst(mapped)
      } else {
        setFormError(err instanceof Error ? err.message : 'Das Speichern ist fehlgeschlagen.')
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <FormDialog
      open={open}
      titleId="service-account-form-title"
      title={account ? `„${account.name}“ bearbeiten` : 'Dienstkonto anlegen'}
      submitLabel={account ? 'Speichern' : createLabel}
      onSubmit={() => void submit()}
      onClose={onClose}
      busy={saving}
      busyAnnouncement="Wird gespeichert"
    >
      <Stack spacing={2.5} sx={{ mt: 1 }}>
        {formError && <Alert severity="error">{formError}</Alert>}
        <TextField
          label="Name"
          value={name}
          onChange={(event) => setName(event.target.value)}
          inputRef={nameRef}
          error={Boolean(errors.name)}
          helperText={errors.name}
          placeholder="Bauantrag-Portal"
          size="small"
          fullWidth
          slotProps={{ htmlInput: { maxLength: 120 } }}
        />
        <TextField
          label="Beschreibung (optional)"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          size="small"
          fullWidth
          multiline
          minRows={2}
          slotProps={{ htmlInput: { maxLength: 1000 } }}
        />
        <Stack spacing={0.75}>
          <FormLabel htmlFor="service-account-responsible" sx={{ fontSize: fontSize.meta }}>
            Verantwortliche Person
          </FormLabel>
          <UserPicker
            inputId="service-account-responsible"
            ariaLabel="Verantwortliche Person"
            placeholder="Person suchen …"
            value={responsible}
            onChange={setResponsible}
            excludedUserIds={[]}
            errorText={errors.responsibleUserId ?? null}
            inputRef={personRef}
            grow
          />
        </Stack>
        <TextField
          label="Anfragen je Stunde (optional)"
          value={rateLimit}
          onChange={(event) => setRateLimit(event.target.value)}
          inputRef={rateRef}
          error={Boolean(errors.rateLimitPerHour)}
          helperText={
            errors.rateLimitPerHour ??
            (channelDefault !== null
              ? `Leer: Vorgabe (${channelDefault}), für alle Tokens zusammen`
              : undefined)
          }
          size="small"
          sx={{ maxWidth: 360 }}
          slotProps={{ htmlInput: { inputMode: 'numeric' } }}
        />
      </Stack>
    </FormDialog>
  )
}
