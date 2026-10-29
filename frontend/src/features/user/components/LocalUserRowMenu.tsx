import { useState } from 'react'
import type { LocalUserResponse } from '@/shared/api/types'
import RowMenu from '@/shared/ui/RowMenu'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { useUserAdminStore } from '../stores/userAdminStore'
import type { SetupLinkHandover } from './SetupLinkDialog'
import { localUserErrorMessage } from './localUserLabels'

export const LOCK_CONSEQUENCE =
  'Das Konto kann sich nicht mehr anmelden, und alle laufenden Sitzungen enden sofort. Die Person ' +
  'wird per E-Mail darüber unterrichtet. Etwaige verbundene Konten enden, ihre Zugangsdaten werden ' +
  'gelöscht, und für etwaige private Bibliotheken beginnt die Löschfrist, ebenso für die Chats ' +
  '(Datum unter „Bearbeiten"), sofern der Betrieb das nicht abgeschaltet hat. Die übrigen ' +
  'Inhalte und Rechte des Kontos bleiben erhalten.'

export const RESET_CONSEQUENCE =
  'Alle Sitzungen des Kontos enden, und die Person erhält einen einmaligen Rücksetzlink per ' +
  'E-Mail. Geht die Nachricht nicht hinaus, wird der Link genau einmal zur Übergabe angezeigt.'

export const GENERATE_PASSWORD_CONSEQUENCE =
  'Es wird ein neues Passwort erzeugt und genau einmal angezeigt. Alle Sitzungen des Kontos enden; ' +
  'bei der nächsten Anmeldung muss das Passwort gewechselt werden.'

export const DELETE_CONSEQUENCE =
  'Sperren ist der Regelweg – Löschen die Ausnahme und nicht umkehrbar. Gelöscht wird nur ein ' +
  'Konto, auf das nichts mehr verweist; mit ihm gehen der persönliche Space, die Zugangsdaten und ' +
  'alle Tokens. Das Protokoll behält seine pseudonymen Zeilen.'

export const SELF_ACTION_TOOLTIP =
  'Das eigene Konto kann hier nicht gesperrt oder gelöscht werden – sonst wäre die Verwaltung ' +
  'nach einem Fehlgriff nicht mehr erreichbar.'

export const BOOTSTRAP_HANDOVER_TOOLTIP =
  'Das Notanker-Konto der Systemverwaltung kann nicht übergeben werden – es ist der Weg zurück in ' +
  'eine Installation ohne funktionierenden Identitätsanbieter und muss deshalb ein lokales Konto ' +
  'bleiben.'

export const BOOTSTRAP_LOCK_DELETE_TOOLTIP =
  'Das Notanker-Konto der Systemverwaltung kann weder gesperrt noch gelöscht werden – es ist der ' +
  'Weg zurück in eine Installation ohne funktionierenden Identitätsanbieter.'

export const BOOTSTRAP_PASSWORD_TOOLTIP =
  'Das Passwort des Notanker-Kontos der Systemverwaltung wird nicht von der Verwaltung ' +
  'zurückgesetzt – sonst wäre das versiegelt hinterlegte Passwort unbemerkt wertlos. Das Konto ' +
  'ändert sein Passwort selbst.'

export const AUDITOR_CREDENTIALS_TOOLTIP =
  'Passwort, Adresse und Anmeldung eines Kontos der Revision ändert nur die Person selbst – sonst ' +
  'hielte die Systemverwaltung Zugang zum Revisionsprotokoll.'

interface LocalUserRowMenuProps {
  user: LocalUserResponse
  /** The caller's own row: locking and deleting it is refused by the backend anyway (409). */
  isSelf: boolean
  onEdit: (user: LocalUserResponse) => void
  onSetupLink: (handover: SetupLinkHandover) => void
  onGeneratedPassword: (user: LocalUserResponse, password: string) => void
  /** Opens the handover dialog; the act itself is the person's, not the menu's. */
  onHandover: (user: LocalUserResponse) => void
}

/**
 * The row menu of a local account. Every action except „Entsperren" presents its
 * consequence beforehand in the confirmation overlay (guideline 5.10); the one-time displays –
 * link and generated password – run through their own dialog.
 *
 * The menu closes before the overlay opens: two stacked layers with their own
 * focus trap could otherwise be left neither with the keyboard nor with a screen reader.
 *
 * „Entsperren" appears only on a locked account (otherwise 409 `NOT_LOCKED`), „Sperren" on every
 * account not yet locked by the administration (otherwise 409 `ALREADY_LOCKED`), „Löschen" is
 * subordinate below a divider: locking is the regular path (ADR-0033, Entscheidung 11).
 */
export default function LocalUserRowMenu({
  user,
  isSelf,
  onEdit,
  onSetupLink,
  onGeneratedPassword,
  onHandover,
}: LocalUserRowMenuProps) {
  const lockUser = useUserAdminStore((s) => s.lockUser)
  const unlockUser = useUserAdminStore((s) => s.unlockUser)
  const deleteUser = useUserAdminStore((s) => s.deleteUser)
  const resetUserPassword = useUserAdminStore((s) => s.resetUserPassword)
  const generateUserPassword = useUserAdminStore((s) => s.generateUserPassword)
  const [busy, setBusy] = useState(false)

  async function run(action: () => Promise<string>, fallback: string) {
    setBusy(true)
    try {
      notify(await action(), 'success')
    } catch (err) {
      notify(localUserErrorMessage(err, fallback), 'error')
    } finally {
      setBusy(false)
    }
  }

  async function lock() {
    if (
      !(await confirmAction({
        question: `„${user.displayName}“ sperren?`,
        consequence: LOCK_CONSEQUENCE,
        confirmLabel: 'Sperren',
        tone: 'caution',
      }))
    ) {
      return
    }
    void run(async () => {
      await lockUser(user.id)
      return `„${user.displayName}“ wurde gesperrt.`
    }, 'Das Konto konnte nicht gesperrt werden.')
  }

  function unlock() {
    void run(async () => {
      await unlockUser(user.id)
      return `„${user.displayName}“ wurde entsperrt.`
    }, 'Das Konto konnte nicht entsperrt werden.')
  }

  async function resetPassword() {
    if (
      !(await confirmAction({
        question: `Passwort von „${user.displayName}“ zurücksetzen?`,
        consequence: RESET_CONSEQUENCE,
        confirmLabel: 'Zurücksetzen',
        tone: 'caution',
      }))
    ) {
      return
    }
    void run(async () => {
      const result = await resetUserPassword(user.id)
      if (result.setupUrl) {
        onSetupLink({
          user,
          url: result.setupUrl,
          deliveryPath: result.deliveryPath,
          kind: 'RESET',
        })
        return `Für „${user.displayName}“ liegt ein Rücksetzlink zur Übergabe bereit.`
      }
      return `Der Rücksetzlink wurde an ${user.email} versendet.`
    }, 'Das Zurücksetzen ist fehlgeschlagen.')
  }

  async function generatePassword() {
    if (
      !(await confirmAction({
        question: `Passwort für „${user.displayName}“ erzeugen?`,
        consequence: GENERATE_PASSWORD_CONSEQUENCE,
        confirmLabel: 'Erzeugen',
        tone: 'caution',
      }))
    ) {
      return
    }
    void run(async () => {
      const result = await generateUserPassword(user.id)
      onGeneratedPassword(user, result.password)
      return `Für „${user.displayName}“ wurde ein Passwort erzeugt.`
    }, 'Das Passwort konnte nicht erzeugt werden.')
  }

  async function remove() {
    if (
      !(await confirmAction({
        question: `„${user.displayName}“ löschen?`,
        consequence: DELETE_CONSEQUENCE,
        confirmLabel: 'Löschen',
        tone: 'danger',
      }))
    ) {
      return
    }
    void run(async () => {
      await deleteUser(user.id)
      return `„${user.displayName}“ wurde gelöscht.`
    }, 'Das Konto konnte nicht gelöscht werden.')
  }

  // A lock for inactivity or failed sign-ins can be turned into one by the administration, which
  // alone starts the deletion period of the chats.
  const lockedByAdministration = user.status === 'LOCKED' && user.lockedReason === 'ADMIN'
  const lockDisabled = isSelf || user.bootstrap
  // an auditor account, or one with an open request for the role, keeps its credentials with its
  // person; the backend refuses these acts with AUDITOR_ACCOUNT
  const auditorAccount = user.systemRole === 'AUDITOR' || user.pendingSystemRole === 'AUDITOR'
  const handoverDisabled = user.bootstrap || auditorAccount
  const passwordDisabled = user.bootstrap || auditorAccount
  const credentialsReason = auditorAccount
    ? AUDITOR_CREDENTIALS_TOOLTIP
    : BOOTSTRAP_PASSWORD_TOOLTIP
  const deleteDisabled = isSelf || user.bootstrap
  const disabledReason = isSelf ? SELF_ACTION_TOOLTIP : BOOTSTRAP_LOCK_DELETE_TOOLTIP

  return (
    <RowMenu
      name={user.displayName}
      disabled={busy}
      items={[
        { label: 'Bearbeiten', onClick: () => onEdit(user) },
        user.status === 'LOCKED' && {
          label: 'Entsperren',
          onClick: unlock,
        },
        !lockedByAdministration && {
          label: 'Sperren',
          onClick: () => void lock(),
          disabled: lockDisabled,
          disabledReason: disabledReason,
        },
        {
          label: 'Rücksetz-Link per E-Mail',
          onClick: () => void resetPassword(),
          disabled: passwordDisabled,
          disabledReason: credentialsReason,
        },
        {
          label: 'Passwort erzeugen',
          onClick: () => void generatePassword(),
          disabled: passwordDisabled,
          disabledReason: credentialsReason,
        },
        {
          label: 'An Identitätsanbieter übergeben',
          onClick: () => onHandover(user),
          disabled: handoverDisabled,
          disabledReason: auditorAccount ? AUDITOR_CREDENTIALS_TOOLTIP : BOOTSTRAP_HANDOVER_TOOLTIP,
        },
        {
          label: 'Löschen',
          onClick: () => void remove(),
          danger: true,
          disabled: deleteDisabled,
          disabledReason: disabledReason,
        },
      ]}
    />
  )
}
