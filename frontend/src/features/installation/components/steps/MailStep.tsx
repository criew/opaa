import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Typography from '@mui/material/Typography'
import type { MailEncryption } from '@/shared/api/types'
import PublicBaseUrlMissingAlert from '@/shared/ui/smtp/PublicBaseUrlMissingAlert'
import {
  SMTP_DEFAULT_ENCRYPTION,
  SMTP_ENCRYPTION_OPTIONS,
  SMTP_LABELS,
  SMTP_TEXTS,
  isMailAddress,
  parseSmtpPort,
  smtpTargetChanged,
} from '@/shared/ui/smtp/smtpSettings'
import type {
  InstallationStepCheckRequest,
  InstallationStepState,
  StepInput,
} from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { apiKeyPart } from '../../utils/apiKey'
import { STEP_INFO } from '../../utils/steps'
import { compact, isBlank } from '../../utils/validation'
import ApiKeyField, { type StoredSecretWording } from '../ApiKeyField'
import FormField from '../FormField'
import StepFrame from '../StepFrame'

const MAIL_FIELDS = [
  'host',
  'port',
  'encryption',
  'username',
  'password',
  'fromAddress',
  'fromName',
  'testRecipient',
]

const PASSWORD_WORDING: StoredSecretWording = {
  label: SMTP_LABELS.password,
  removed: 'Das gespeicherte Passwort wird beim Speichern gelöscht.',
  keep: 'Löschen des Passworts abbrechen',
  remove: 'Gespeichertes Passwort löschen',
}

const TEST_RECIPIENT_INVALID =
  'Die Adresse der Testnachricht muss eine E-Mail-Adresse sein, zum Beispiel it-betrieb@stadt.example.'

interface MailStepProps {
  step: InstallationStepState
  /** Whether `OPAA_PUBLIC_BASE_URL` is set; without it no mail carries a usable link. */
  publicBaseUrlConfigured: boolean
  submit: (
    values: StepInput['MAIL'],
    checkOptions: InstallationStepCheckRequest,
  ) => Promise<WizardResult>
  /** Skips the step in the backend, so the completion leaves sending off. */
  skip: () => Promise<WizardResult>
  onBack?: () => void
}

/**
 * The optional mail server, with the fields and rules of „Administration → E-Mail". Its check
 * connects and signs in without sending; a test message goes out only to an address typed here.
 * Skipping discards what was saved, and sending stays off until the administration sets it up.
 */
export default function MailStep({
  step,
  publicBaseUrlConfigured,
  submit,
  skip,
  onBack,
}: MailStepProps) {
  const id = useId()
  const stored = step.mail ?? null
  const [host, setHost] = useState(stored?.host ?? '')
  const [port, setPort] = useState(stored?.port == null ? '' : String(stored.port))
  const [encryption, setEncryption] = useState<MailEncryption>(
    stored?.encryption ?? SMTP_DEFAULT_ENCRYPTION,
  )
  const [username, setUsername] = useState(stored?.username ?? '')
  const [password, setPassword] = useState('')
  const [removePassword, setRemovePassword] = useState(false)
  const [fromAddress, setFromAddress] = useState(stored?.fromAddress ?? '')
  const [fromName, setFromName] = useState(stored?.fromName ?? '')
  const [testRecipient, setTestRecipient] = useState('')
  const form = useStepForm(step.checked ? null : step.checkMessage)
  const encryptionLabelId = `${id}-encryption`

  const portNumber = parseSmtpPort(port)
  const passwordStored = Boolean(stored?.passwordSet)
  const passwordNeeded =
    passwordStored &&
    !removePassword &&
    password === '' &&
    stored !== null &&
    smtpTargetChanged(
      {
        host: stored.host,
        port: stored.port,
        username: stored.username ?? null,
        encryption: stored.encryption,
      },
      { host, port: portNumber, username, encryption },
    )

  function portError(): string | undefined {
    if (portNumber === null) return SMTP_TEXTS.portMissing
    return Number.isNaN(portNumber) ? SMTP_TEXTS.portInvalid : undefined
  }

  function fromAddressError(): string | undefined {
    if (isBlank(fromAddress)) return SMTP_TEXTS.fromAddressMissing
    return isMailAddress(fromAddress) ? undefined : SMTP_TEXTS.fromAddressInvalid
  }

  function handleSubmit() {
    const localErrors = compact({
      host: isBlank(host) ? SMTP_TEXTS.hostMissing : undefined,
      port: portError(),
      password: passwordNeeded ? SMTP_TEXTS.passwordFollowsTarget : undefined,
      fromAddress: fromAddressError(),
      testRecipient:
        isBlank(testRecipient) || isMailAddress(testRecipient) ? undefined : TEST_RECIPIENT_INVALID,
    })
    const recipient = testRecipient.trim()
    const { apiKey: passwordPart } = apiKeyPart(password, removePassword)
    void form.submit(localErrors, MAIL_FIELDS, () =>
      submit(
        {
          host: host.trim(),
          port: portNumber ?? 0,
          encryption,
          username: isBlank(username) ? null : username.trim(),
          ...(passwordPart === undefined ? {} : { password: passwordPart }),
          fromAddress: fromAddress.trim(),
          fromName: isBlank(fromName) ? null : fromName.trim(),
        },
        recipient === '' ? {} : { testRecipient: recipient },
      ),
    )
  }

  function passwordHint(): string {
    if (passwordNeeded) return SMTP_TEXTS.passwordFollowsTarget
    return passwordStored ? SMTP_TEXTS.passwordStored : SMTP_TEXTS.passwordNone
  }

  return (
    <StepFrame
      title={STEP_INFO.MAIL.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.MAIL.laterUnder}
      description={
        <>
          Der Mailserver, über den OPAA Einladungen, Rücksetzlinks und Hinweise versendet. Der
          Schritt ist optional: Übersprungen versendet OPAA nichts, und der Server lässt sich später
          unter „Administration → E-Mail“ eintragen. Die Prüfung baut eine Verbindung auf und meldet
          sich bei Bedarf an, ohne eine Nachricht zu senden.
        </>
      }
      failure={form.failure}
      busy={form.busy}
      busyAnnouncement="Verbindung zum Mailserver wird geprüft"
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={() => void form.submit({}, MAIL_FIELDS, skip)}
    >
      {!publicBaseUrlConfigured && <PublicBaseUrlMissingAlert />}
      <FormField
        id={`${id}-host`}
        label={SMTP_LABELS.host}
        value={host}
        onChange={setHost}
        error={form.errors.host}
        hint={SMTP_TEXTS.hostHint}
      />
      <FormField
        id={`${id}-port`}
        label={SMTP_LABELS.port}
        inputMode="numeric"
        value={port}
        onChange={setPort}
        error={form.errors.port}
        hint={SMTP_TEXTS.portHint}
      />
      <Box>
        <Typography
          id={encryptionLabelId}
          sx={{ fontSize: 12, color: 'text.secondary', mb: '5px' }}
        >
          {SMTP_LABELS.encryption}
        </Typography>
        <RadioGroup
          value={encryption}
          onChange={(event) => setEncryption(event.target.value as MailEncryption)}
          aria-labelledby={encryptionLabelId}
        >
          {SMTP_ENCRYPTION_OPTIONS.map((option) => (
            <FormControlLabel
              key={option.value}
              value={option.value}
              control={<Radio size="small" />}
              sx={{ alignItems: 'flex-start', mb: 1, '& .MuiRadio-root': { mt: -0.5 } }}
              label={
                <Box>
                  <Typography sx={{ fontSize: 14, fontWeight: 500 }}>{option.label}</Typography>
                  <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                    {option.hint}
                  </Typography>
                </Box>
              }
            />
          ))}
        </RadioGroup>
      </Box>
      <FormField
        id={`${id}-username`}
        label={SMTP_LABELS.username}
        optional
        autoComplete="off"
        value={username}
        onChange={setUsername}
        error={form.errors.username}
        hint={SMTP_TEXTS.usernameHint}
      />
      <ApiKeyField
        id={`${id}-password`}
        keyStored={passwordStored}
        value={password}
        onChange={setPassword}
        remove={removePassword}
        onRemoveChange={setRemovePassword}
        error={form.errors.password}
        hint={passwordHint()}
        wording={PASSWORD_WORDING}
        autoComplete="new-password"
      />
      <FormField
        id={`${id}-from-address`}
        label={SMTP_LABELS.fromAddress}
        inputMode="email"
        value={fromAddress}
        onChange={setFromAddress}
        error={form.errors.fromAddress}
        hint={SMTP_TEXTS.fromAddressHint}
      />
      <FormField
        id={`${id}-from-name`}
        label={SMTP_LABELS.fromName}
        optional
        value={fromName}
        onChange={setFromName}
        error={form.errors.fromName}
        hint={SMTP_TEXTS.fromNameHint}
      />
      <FormField
        id={`${id}-test-recipient`}
        label="Testnachricht an"
        optional
        inputMode="email"
        autoComplete="off"
        value={testRecipient}
        onChange={setTestRecipient}
        error={form.errors.testRecipient}
        hint="Mit einer Adresse sendet die Prüfung die Testnachricht dorthin; leer sendet sie nichts. Die Adresse wird nicht gespeichert."
      />
    </StepFrame>
  )
}
