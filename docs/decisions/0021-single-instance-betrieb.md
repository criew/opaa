# ADR-0021: OPAA ist bis auf Weiteres Single-Instance

## Status

Ersetzt durch [ADR-0053](0053-mehrinstanzbetrieb-aktiv-aktiv.md) · zuletzt geändert 2026-10-20

## Entscheidung

| Entscheidung | Verbleib |
|---|---|
| 1. Genau ein Backend-Prozess je Installation | Entfallen mit #2952, siehe ADR-0053 Entscheidung 1. |
| 2. Register in `backend/docs/`, Verweis in der Javadoc | Entfallen mit #2952, siehe ADR-0053 Entscheidung 16. |
| 3. Serialisierung gegen Doppelstarts geht über die Datenbank | Entfallen mit #2952, siehe ADR-0053 Entscheidung 2. |
| 4. Keine Sitzungsaffinität, kein Instanzabgleich | Entfallen mit #2952, siehe ADR-0053 Entscheidungen 1 und 4. |
