import { create } from 'zustand'

interface CopyAnnouncement {
  text: string
  /** Counts the announcements, so the same sentence twice in a row is still a change. */
  sequence: number
}

/**
 * The one place that tells a screen reader a copy from the chat history worked. A live region per
 * copy button would put dozens of them into a long chat; MessageList renders a single one.
 */
export const useCopyAnnouncement = create<CopyAnnouncement>(() => ({ text: '', sequence: 0 }))

export function announceCopy(text: string) {
  useCopyAnnouncement.setState((state) => ({ text, sequence: state.sequence + 1 }))
}
