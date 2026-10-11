import type { ConnectorTypeStateResponse, OwnAddressStock } from '../../../types/api'

/** How a source type relates to profiles, in the words of the overview. */
export function profileRuleLabel(state: ConnectorTypeStateResponse): string {
  switch (state.profileSupport) {
    case 'FORBIDDEN':
      return 'Keine Zugänge möglich'
    case 'REQUIRED':
      return 'Immer über Zugänge'
    case 'OPTIONAL':
      return state.profileRequired ? 'Nur über Zugänge' : 'Zugang oder eigene Adresse'
    default: {
      const unknown: never = state.profileSupport
      throw new Error(`Unknown profile support ${String(unknown)}`)
    }
  }
}

/** What happens to the libraries with an own address while „Nur über Zugänge" holds. */
export function stockLabel(stock: OwnAddressStock | null | undefined): string {
  if (stock === 'LOCKED') return 'Bibliotheken mit eigener Adresse sind gesperrt.'
  return 'Bibliotheken mit eigener Adresse laufen weiter, ihre Adresse ist eingefroren.'
}
