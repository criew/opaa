import Stack from '@mui/material/Stack'
import type { MailEncryption } from '../../api/types'
import FormFieldRow from '../form/FormFieldRow'
import SecretField from '../form/SecretField'
import { SMTP_ENCRYPTION_OPTIONS, SMTP_LABELS, SMTP_TEXTS } from '../smtp/smtpSettings'
import { smtpPasswordNeeded, type SmtpDraft, type StoredSmtp } from './setupDrafts'
import OptionGroup from './OptionGroup'
import SetupField from './SetupField'
import { SETUP_RHYTHM, type SetupErrors } from './setupNames'

interface SmtpFieldsProps {
  idPrefix: string
  value: SmtpDraft
  onChange: (patch: Partial<SmtpDraft>) => void
  /** Errors by `host`, `port`, `username`, `password`, `fromAddress` and `fromName`. */
  errors?: SetupErrors
  stored: StoredSmtp | null
}

/**
 * The mail server as the assistant's step „E-Mail“ and „Administration → E-Mail“ ask for it:
 * server and port as one pair, encryption, sign-in and sender.
 */
export default function SmtpFields({
  idPrefix,
  value,
  onChange,
  errors = {},
  stored,
}: SmtpFieldsProps) {
  const passwordNeeded = smtpPasswordNeeded(stored, value)
  return (
    <Stack spacing={SETUP_RHYTHM.betweenFields}>
      <FormFieldRow>
        <SetupField
          id={`${idPrefix}-host`}
          label={SMTP_LABELS.host}
          placeholder="smtp.intern.example"
          autoComplete="off"
          value={value.host}
          onChange={(host) => onChange({ host })}
          error={errors.host}
        />
        <SetupField
          id={`${idPrefix}-port`}
          label={SMTP_LABELS.port}
          numeric
          fullWidth={false}
          inputMode="numeric"
          value={value.port}
          onChange={(port) => onChange({ port })}
          error={errors.port}
          hint={SMTP_TEXTS.portHint}
        />
      </FormFieldRow>
      <OptionGroup<MailEncryption>
        label={SMTP_LABELS.encryption}
        options={SMTP_ENCRYPTION_OPTIONS}
        value={value.encryption}
        onChange={(encryption) => onChange({ encryption })}
      />
      <SetupField
        id={`${idPrefix}-username`}
        label={SMTP_LABELS.username}
        optional
        autoComplete="off"
        value={value.username}
        onChange={(username) => onChange({ username })}
        error={errors.username}
        hint={SMTP_TEXTS.usernameHint}
      />
      <SecretField
        id={`${idPrefix}-password`}
        label={SMTP_LABELS.password}
        optional
        value={value.password}
        onChange={(password) => onChange({ password })}
        stored={Boolean(stored?.passwordSet)}
        error={errors.password ?? (passwordNeeded ? SMTP_TEXTS.passwordFollowsTarget : undefined)}
        removal={{
          requested: value.removePassword,
          onChange: (removePassword) => onChange({ removePassword }),
          removeLabel: SMTP_TEXTS.passwordRemove,
          keepLabel: SMTP_TEXTS.passwordKeep,
          removedText: SMTP_TEXTS.passwordRemoved,
        }}
      />
      <SetupField
        id={`${idPrefix}-from-address`}
        label={SMTP_LABELS.fromAddress}
        inputMode="email"
        placeholder="opaa@stadt.example"
        value={value.fromAddress}
        onChange={(fromAddress) => onChange({ fromAddress })}
        error={errors.fromAddress}
      />
      <SetupField
        id={`${idPrefix}-from-name`}
        label={SMTP_LABELS.fromName}
        optional
        value={value.fromName}
        onChange={(fromName) => onChange({ fromName })}
        error={errors.fromName}
      />
    </Stack>
  )
}
