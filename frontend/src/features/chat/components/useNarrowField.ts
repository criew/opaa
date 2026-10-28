import { useEffect, useState } from 'react'

/**
 * Whether the element's measured content width is below `threshold` pixels. It follows the
 * element itself, not the window - a wide sidebar narrows the field without changing the window.
 * Without a measurement (no layout, no ResizeObserver) the element counts as wide.
 */
export function useNarrowField(element: HTMLElement | null, threshold: number): boolean {
  const [narrow, setNarrow] = useState(false)
  useEffect(() => {
    if (!element || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver((entries) => {
      const width = entries[entries.length - 1]?.contentRect.width ?? 0
      setNarrow(width > 0 && width < threshold)
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [element, threshold])
  return narrow
}
