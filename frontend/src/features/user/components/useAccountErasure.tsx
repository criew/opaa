import { useState } from 'react'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { useRecentLoginGate } from '@/shared/session/useRecentLoginGate'
import { useUserAdminStore } from '../stores/userAdminStore'
import { localUserErrorMessage } from './localUserLabels'

export const ERASE_LABEL = 'Personenbezug entfernen …'

export const ERASE_CONSEQUENCE =
  'Für ein Löschbegehren nach der DSGVO, nicht umkehrbar. Name, Adresse und Kennung beim ' +
  'Identitätsanbieter werden entfernt; gelöscht werden die Chats, der persönliche Space, die ' +
  'privaten Bibliotheken, Anmeldedaten und Tokens. Mitgliedschaften, Rechte, geteilte Inhalte und ' +
  'das Protokoll bleiben und nennen nur noch „Gelöschtes Konto“.'

export const ERASE_NOT_DEACTIVATED_TOOLTIP =
  'Nur ein gesperrtes oder abgelaufenes Konto verliert seinen Personenbezug. Sperren Sie es zuerst.'

export const ERASE_SYSTEM_ROLE_TOOLTIP =
  'Das Konto hat eine Systemrolle. Entziehen Sie sie, bevor Sie den Personenbezug entfernen.'

const ERASE_CONFIRM_PURPOSE =
  'Den Personenbezug eines Kontos zu entfernen, verlangt eine gerade bestätigte Anmeldung. Bitte ' +
  'bestätigen Sie Ihre Anmeldung.'

/**
 * The erasure of an account from a row menu, local or of a provider: the consequence in the
 * confirmation overlay first, then the act behind the recent-login gate. `dialog` must be
 * rendered.
 */
export function useAccountErasure() {
  const eraseUser = useUserAdminStore((s) => s.eraseUser)
  const [busy, setBusy] = useState(false)
  const gate = useRecentLoginGate(ERASE_CONFIRM_PURPOSE)

  async function run(id: string, name: string, afterConfirmation = false) {
    setBusy(true)
    try {
      await eraseUser(id)
      gate.settled()
      notify(`Der Personenbezug von „${name}“ wurde entfernt.`, 'success')
    } catch (err) {
      const refused = gate.refusal(err, () => void run(id, name, true), afterConfirmation)
      if (refused === null) {
        notify(
          localUserErrorMessage(err, 'Der Personenbezug konnte nicht entfernt werden.'),
          'error',
        )
      } else if (refused) {
        notify(refused, 'error')
      }
    } finally {
      setBusy(false)
    }
  }

  async function erase(id: string, name: string) {
    if (
      !(await confirmAction({
        question: `Personenbezug von „${name}“ entfernen?`,
        consequence: ERASE_CONSEQUENCE,
        confirmLabel: 'Entfernen',
        tone: 'danger',
      }))
    ) {
      return
    }
    void run(id, name)
  }

  return { erase, busy, dialog: gate.dialog }
}
