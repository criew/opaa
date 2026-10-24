import { useEffect, useState } from 'react'

/** `flag`, but turning true only after it has held for `delayMs` - no flicker on fast answers. */
export function useDelayedFlag(flag: boolean, delayMs = 300): boolean {
  const [shown, setShown] = useState(false)
  useEffect(() => {
    if (!flag) {
      setShown(false)
      return
    }
    const timer = setTimeout(() => setShown(true), delayMs)
    return () => clearTimeout(timer)
  }, [flag, delayMs])
  return flag && shown
}
