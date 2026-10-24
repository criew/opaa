import { createContext, useState } from 'react'

/**
 * Lets a wizard or dialog make sure the connection of the shown form was tested before it moves
 * on: „Weiter" and „Speichern" call `ensure`, which runs the test unless a passed result for the
 * current fields stands.
 */
export interface ConnectionGate {
  register: (ensure: (() => Promise<boolean>) | null) => void
}

export const ConnectionGateContext = createContext<ConnectionGate | null>(null)

/** A gate for the surrounding wizard or dialog; `ensure` is true when no form registered. */
export function useConnectionGateHost() {
  const [host] = useState(() => {
    let registered: (() => Promise<boolean>) | null = null
    const gate: ConnectionGate = {
      register: (ensure) => {
        registered = ensure
      },
    }
    return { gate, ensure: async () => (registered ? registered() : true) }
  })
  return host
}
