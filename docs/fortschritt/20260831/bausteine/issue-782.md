# Issue #782 — Chat-Fußzeile zählt lesbare statt effektiv durchsuchte Bestände in Spaces mit Zuordnungen
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, frontend
- PRs: #783 (2026-08-23)

**Laut Issue:** In einem Space mit Space↔Bibliothek-Zuordnungen (#706) zeigte die Chat-Fußzeile bei @Alles-Wissen die Zahl aller lesbaren Bibliotheken der Person statt der effektiven Schnittmenge aus Zuordnung und Lesbarkeit — das Backend verengte korrekt, die Anzeige nicht. Erwartet war, dass die Fußzeile in einem Space mit Zuordnungen die Schnittmenge zählt (z. B. „1 zugeordneter Bestand") und ohne Zuordnungen wie bisher alle lesbaren.

**Geliefert:** Wie gefordert. `ChatInput` lädt jetzt selbst die Bibliothekszuordnungen des Spaces über `useSpaceStore#loadLibraryAssociations` und zählt bei @Alles-Wissen die Teilmenge mit `readableByCaller = true`, sobald der Space kuratiert ist. Vier neue Tests decken Singular, Plural, den „nichts lesbar"-Grenzfall und den unveränderten Fall ohne Zuordnungen ab.

**Verifikation:** Die heutige Formulierung in `frontend/src/components/chat/ChatInput.tsx` lautet „zugeordneter lesbarer Bestand"/„zugeordnete lesbare Bestände" (Zeile ~190) — eine später präzisierte Textvariante desselben gelieferten Verhaltens, keine inhaltliche Abweichung.

**Themen:** spaces, retrieval, frontend
