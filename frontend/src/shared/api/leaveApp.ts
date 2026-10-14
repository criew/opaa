/** Sends this tab to an address outside the application, such as a provider's consent page. */
export function leaveFor(url: string): void {
  window.location.assign(url)
}
