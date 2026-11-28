import { existsSync, readFileSync } from "node:fs";

/**
 * What the backend logs of one run say about the disturbance: counts of the lines that tell how
 * heartbeat, invalidation bus and pool lived through it, and the distinct warnings and errors.
 */

const PATTERNS: Record<string, RegExp> = {
  busLost: /The invalidation bus does not listen/,
  busBack: /Listening on the invalidation bus again/,
  heartbeatFailed: /heartbeat failed/,
  instanceLost: /counts as dead in the register/,
  poolTimeout: /Connection is not available, request timed out/,
  poolBroken: /marked as broken|Failed to validate connection|Connection .* is closed/,
  readOnly: /read-only transaction|cannot execute .* in a read-only|cannot execute LISTEN during recovery/,
  scheduledFailed: /Scheduled run .* failed|scheduled.*failed/i,
  recovered: /abandon|gave up|recover/i,
};

export interface LogFindings {
  pods: number;
  lines: number;
  counts: Record<string, number>;
  /** Per loss of the listening connection, the time until it listened again, from the log. */
  busGapsMs: number[];
  /** Distinct WARN and ERROR messages, digits and identifiers masked, with their count. */
  messages: { level: string; message: string; count: number }[];
}

function normalize(message: string): string {
  return message
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g, "<id>")
    .replace(/\d+/g, "<n>")
    .slice(0, 220);
}

/** Reads the log files, keeping the lines at or after `since` (kubectl --timestamps prefix). */
export function analyzeLogs(files: string[], since: number): LogFindings {
  const counts = Object.fromEntries(Object.keys(PATTERNS).map((key) => [key, 0]));
  const messages = new Map<string, { level: string; message: string; count: number }>();
  const busGapsMs: number[] = [];
  let lines = 0;
  for (const file of files.filter((path) => existsSync(path))) {
    let lostAt: number | null = null;
    for (const line of readFileSync(file, "utf8").split("\n")) {
      const stamp = Date.parse(line.slice(0, line.indexOf(" ")).replace(/(\.\d{3})\d*Z$/, "$1Z"));
      if (Number.isNaN(stamp) || stamp < since) {
        continue;
      }
      lines += 1;
      if (PATTERNS.busLost.test(line) && lostAt === null) {
        lostAt = stamp;
      } else if (PATTERNS.busBack.test(line) && lostAt !== null) {
        busGapsMs.push(stamp - lostAt);
        lostAt = null;
      }
      for (const [key, pattern] of Object.entries(PATTERNS)) {
        if (pattern.test(line)) {
          counts[key] += 1;
        }
      }
      const level = /\s(WARN|ERROR)\s/.exec(line)?.[1];
      if (level) {
        const text = line.includes(" : ") ? line.slice(line.indexOf(" : ") + 3) : line;
        const key = `${level} ${normalize(text)}`;
        const entry = messages.get(key) ?? { level, message: normalize(text), count: 0 };
        entry.count += 1;
        messages.set(key, entry);
      }
    }
  }
  return {
    pods: files.length,
    lines,
    counts,
    busGapsMs,
    messages: [...messages.values()].sort((a, b) => b.count - a.count).slice(0, 25),
  };
}
