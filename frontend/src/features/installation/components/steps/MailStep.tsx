import { useId, useState } from 'react'
import {
  secretPart,
  smtpDraftOf,
  smtpPasswordNeeded,
  type SmtpDraft,
  type StoredSmtp,
} from '@/shared/ui/setup/setupDrafts'
import SetupField from '@/shared/ui/setup/SetupField'
import SmtpFields from '@/shared/ui/setup/SmtpFields'
import PublicBaseUrlMissingAlert from '@/shared/ui/smtp/PublicBaseUrlMissingAlert'
import { SMTP_TEXTS, isMailAddress, parseSmtpPort } from '@/shared/ui/smtp/smtpSettings'
import type {
  InstallationStepCheckRequest,
  InstallationStepState,
  StepInput,
} from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { STEP_INFO } from '../../utils/steps'
import { compact, isBlank } from '../../utils/validation'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

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

const TEST_RECIPIENT_INVALID =
  'Die Adresse der Testnachricht muss eine E-Mail-Adresse sein, zum Beispiel it-betrieb@stadt.example.'

interface MailStepProps extends StepNavigationProps {
  step: InstallationStepState
  /** Whether `OPAA_PUBLIC_BASE_URL` is set; without it no mail carries a usable link. */
  publicBaseUrlConfigured: boolean
  submit: (
    values: StepInput['MAIL'],
    checkOptions: InstallationStepCheckRequest,
  ) => Promise<WizardResult>
}

function storedOf(step: InstallationStepState): StoredSmtp | null {
  const mail = step.mail
  if (!mail) return null
  return {
    host: mail.host,
    port: mail.port,
    username: mail.username ?? null,
    encryption: mail.encryption,
    fromAddress: mail.fromAddress,
    fromName: mail.fromName,
    passwordSet: Boolean(mail.passwordSet),
  }
}

/**
 * The optional mail server, with the fields and rules of „Administration → E-Mail“. Its check
 * connects and signs in without sending; a test message goes out only to an address typed under
 * „Testnachricht an“, which exists here alone: before the installation is complete there is no
 * account whose address the administration's test could use.
 */
export default function MailStep({
  step,
  publicBaseUrlConfigured,
  submit,
  onBack,
  onSkip,
}: MailStepProps) {
  const id = useId()
  const [stored] = useState(() => storedOf(step))
  const [draft, setDraft] = useState<SmtpDraft>(() => smtpDraftOf(stored))
  const [testRecipient, setTestRecipient] = useState('')
  const form = useStepForm(step.checked ? null : step.checkMessage)
  const portNumber = parseSmtpPort(draft.port)

  function portError(): string | undefined {
    if (portNumber === null) return SMTP_TEXTS.portMissing
    return Number.isNaN(portNumber) ? SMTP_TEXTS.portInvalid : undefined
  }

  function fromAddressError(): string | undefined {
    if (isBlank(draft.fromAddress)) return SMTP_TEXTS.fromAddressMissing
    return isMailAddress(draft.fromAddress) ? undefined : SMTP_TEXTS.fromAddressInvalid
  }

  function handleSubmit() {
    const localErrors = compact({
      host: isBlank(draft.host) ? SMTP_TEXTS.hostMissing : undefined,
      port: portError(),
      password: smtpPasswordNeeded(stored, draft) ? SMTP_TEXTS.passwordFollowsTarget : undefined,
      fromAddress: fromAddressError(),
      testRecipient:
        isBlank(testRecipient) || isMailAddress(testRecipient) ? undefined : TEST_RECIPIENT_INVALID,
    })
    const recipient = testRecipient.trim()
    const password = secretPart(draft.password, draft.removePassword)
    void form.submit(localErrors, MAIL_FIELDS, () =>
      submit(
        {
          host: draft.host.trim(),
          port: portNumber ?? 0,
          encryption: draft.encryption,
          username: isBlank(draft.username) ? null : draft.username.trim(),
          ...(password === undefined ? {} : { password }),
          fromAddress: draft.fromAddress.trim(),
          fromName: isBlank(draft.fromName) ? null : draft.fromName.trim(),
        },
        recipient === '' ? {} : { testRecipient: recipient },
      ),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.MAIL.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.MAIL.laterUnder}
      description="Der Mailserver, über den OPAA Einladungen, Rücksetzlinks und Hinweise versendet. Übersprungen versendet OPAA nichts, bis die Systemverwaltung ihn unter „Administration → E-Mail“ einträgt."
      failure={form.failure}
      busy={form.busy}
      busyAnnouncement="Verbindung zum Mailserver wird geprüft"
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      {!publicBaseUrlConfigured && <PublicBaseUrlMissingAlert />}
      <SmtpFields
        idPrefix={id}
        value={draft}
        onChange={(patch) => setDraft((current) => ({ ...current, ...patch }))}
        errors={form.errors}
        stored={stored}
      />
      <SetupField
        id={`${id}-test-recipient`}
        label="Testnachricht an"
        optional
        inputMode="email"
        autoComplete="off"
        value={testRecipient}
        onChange={setTestRecipient}
        error={form.errors.testRecipient}
        hint="Ohne Adresse prüft der Schritt nur die Verbindung und sendet nichts; die Adresse wird nicht gespeichert."
      />
    </StepFrame>
  )
}
