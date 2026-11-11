import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import type { CreatedServiceAccountTokenResponse } from '@/shared/api/types'
import { copyOnce } from '@/shared/utils/copyOnce'
import { fieldWidth, fontFamily, fontSize, radius } from '@/shared/theme/tokens'
import { connectionsAdminRoute } from '@/shared/routes'
import { isRecentLoginRequired, recentLoginRefusalMessage } from '@/shared/session/recentLogin'
import RecentLoginDialog from '@/shared/ui/RecentLoginDialog'
import { getExternalAccessSettings } from '../../api/externalAccessApi'
import { createServiceAccountToken } from '../../api/serviceAccountApi'
import {
  earliestExpiryDate,
  expiryInstantOf,
  maxExpiryDate,
  toDateInputValue,
} from '../tokenLabels'
import { useErrorTitle } from './serviceAccountHooks'
import { VALUE_ONCE_HINT, formatDate } from './serviceAccountLabels'
import FormDialog from '@/shared/ui/dialog/FormDialog'
import SecretDialog from '@/shared/ui/dialog/SecretDialog'

export const CONFIRM_PURPOSE =
  'Ein Token eines Dienstkontos wirkt über diese Sitzung hinaus. Bevor Sie es ausstellen, ' +
  'bestätigen Sie bitte Ihre Anmeldung.'

interface IssueServiceAccountTokenDialogProps {
  /** The account the token is for; null keeps the dialog closed. */
  account: { id: string; name: string } | null
  onClose: () => void
  /** Called once the token exists, before its value is shown. */
  onIssued: () => void
  /** Title of the form step; „Erstes Token ausstellen“ right after creating the account. */
  title?: string
}

/** The value, once: mono on a hairline, with the address of the channel. */
export function TokenValue({ created }: { created: CreatedServiceAccountTokenResponse }) {
  const url = `${window.location.origin}/mcp`
  return (
    <Stack spacing={1.5}>
      <Typography sx={{ fontSize: fontSize.text }}>
        „{created.name}“, gültig bis {formatDate(created.expiresAt)}
      </Typography>
      <Box
        data-testid="service-account-token-value"
        sx={{
          fontFamily: fontFamily.mono,
          fontSize: fontSize.text,
          p: 1.5,
          border: 1,
          borderColor: 'divider',
          borderRadius: `${radius.sm}px`,
          overflowWrap: 'anywhere',
        }}
      >
        {created.token}
      </Box>
      <Alert severity="warning">{VALUE_ONCE_HINT}</Alert>
      <Box
        component="dl"
        sx={{ m: 0, fontSize: fontSize.text, '& dt': { color: 'text.secondary' } }}
      >
        <dt>Adresse</dt>
        <Box component="dd" sx={{ m: 0, fontFamily: fontFamily.mono, overflowWrap: 'anywhere' }}>
          {url}
        </Box>
        <Box component="dt" sx={{ mt: 1 }}>
          Kopfzeile
        </Box>
        <Box component="dd" sx={{ m: 0, fontFamily: fontFamily.mono }}>
          Authorization: Bearer …
        </Box>
      </Box>
    </Stack>
  )
}

/**
 * Issue a token for a service account: name and expiry, then the value exactly once in the same
 * dialog. The value step closes neither on Escape nor on a click beside it.
 */
export default function IssueServiceAccountTokenDialog(props: IssueServiceAccountTokenDialogProps) {
  // a fresh dialog per account; a reload of the same account keeps the value step open
  if (!props.account) return null
  return <TokenDialog key={props.account.id} {...props} />
}

function TokenDialog({
  account,
  onClose,
  onIssued,
  title = 'Token ausstellen',
}: IssueServiceAccountTokenDialogProps) {
  const [name, setName] = useState('')
  const [expiry, setExpiry] = useState('')
  const [maxDays, setMaxDays] = useState(90)
  const [errors, setErrors] = useState<{ name?: string; expiry?: string }>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [created, setCreated] = useState<CreatedServiceAccountTokenResponse | null>(null)
  const [confirming, setConfirming] = useState(false)
  const nameRef = useRef<HTMLInputElement>(null)
  const expiryRef = useRef<HTMLInputElement>(null)
  useErrorTitle(Boolean(errors.name || errors.expiry) && !created)

  useEffect(() => {
    void getExternalAccessSettings()
      .then((settings) => {
        setMaxDays(settings.tokenMaxLifetimeDays)
        setExpiry(toDateInputValue(maxExpiryDate(settings.tokenMaxLifetimeDays)))
      })
      .catch(() => setExpiry(toDateInputValue(maxExpiryDate(90))))
  }, [])

  /**
   * Issuing needs a recent sign-in: a refusal opens the confirmation, which repeats the issue
   * once; a second refusal stands as the form's message.
   */
  async function issue(afterConfirmation: boolean) {
    if (!account) return
    const found: { name?: string; expiry?: string } = {}
    if (!name.trim()) found.name = 'Bitte einen Namen angeben.'
    if (!expiry) found.expiry = 'Bitte ein Ablaufdatum angeben.'
    setErrors(found)
    if (found.name) return nameRef.current?.focus()
    if (found.expiry) return expiryRef.current?.focus()
    setSaving(true)
    setFormError(null)
    try {
      const result = await createServiceAccountToken(
        account.id,
        name.trim(),
        expiryInstantOf(expiry, maxExpiryDate(maxDays)),
      )
      setCreated(result)
      onIssued()
    } catch (err) {
      if (isRecentLoginRequired(err) && !afterConfirmation) setConfirming(true)
      else if (isRecentLoginRequired(err)) setFormError(recentLoginRefusalMessage(false))
      else setFormError(err instanceof Error ? err.message : 'Das Token wurde nicht ausgestellt.')
    } finally {
      setSaving(false)
    }
  }

  if (!account) return null

  if (created) {
    return (
      <SecretDialog
        titleId="sa-token-value-title"
        title={`Token für ${account.name}`}
        onCopy={() => void copyOnce(created.token, 'Der Tokenwert')}
        onClose={onClose}
      >
        <TokenValue created={created} />
      </SecretDialog>
    )
  }

  return (
    <>
      <FormDialog
        titleId="sa-token-title"
        title={title}
        submitLabel="Ausstellen"
        onSubmit={() => void issue(false)}
        onClose={onClose}
        busy={saving}
        busyAnnouncement="Token wird ausgestellt"
      >
        <Stack spacing={2.5} sx={{ mt: 1 }}>
          {formError && <Alert severity="error">{formError}</Alert>}
          <Typography sx={{ fontSize: fontSize.text }}>Für „{account.name}“</Typography>
          <FormField
            label="Name"
            placeholder="Produktion"
            value={name}
            onChange={(event) => setName(event.target.value)}
            inputRef={nameRef}
            error={Boolean(errors.name)}
            helperText={errors.name}
            size="small"
            fullWidth
          />
          <FormField
            label="Läuft ab"
            type="date"
            value={expiry}
            onChange={(event) => setExpiry(event.target.value)}
            inputRef={expiryRef}
            error={Boolean(errors.expiry)}
            helperText={errors.expiry ?? `höchstens ${maxDays} Tage`}
            size="small"
            sx={{ maxWidth: fieldWidth.short }}
            slotProps={{
              htmlInput: {
                min: toDateInputValue(earliestExpiryDate()),
                max: toDateInputValue(maxExpiryDate(maxDays)),
              },
            }}
          />
        </Stack>
      </FormDialog>
      <RecentLoginDialog
        open={confirming}
        purpose={CONFIRM_PURPOSE}
        onConfirmed={() => {
          setConfirming(false)
          void issue(true)
        }}
        onClose={() => setConfirming(false)}
        returnTo={`${connectionsAdminRoute('service-accounts')}?account=${account.id}`}
      />
    </>
  )
}
