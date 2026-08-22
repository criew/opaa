# Issue #350 — Cloud-Deployment und Managed Service gegen das Souveränitätsversprechen prüfen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #378 (2026-08-14)

**Laut Issue:** Prüfauftrag aus Epic #344: `docs/features/deployment-infrastructure.md` nannte drei Deployment-Modelle (On-Premises, Private Cloud AWS/Azure/GCP, künftiger Managed Service durch das OPAA-Team). Zu klären: ist Private Cloud für die Zielgruppe realistisch oder untergräbt sie das Souveränitätsversprechen, und passt ein vom Projektteam betreuter Dienst zu einem quelloffenen Produkt. Ergebnis sollte eine Entscheidungsvorlage sein.

**Geliefert:** PR #378 fasst Cloud-Betrieb neu als „Möglichkeit, nicht als Betriebsmodell" — der Abschnitt „Cloud-Deployment und betreuter Dienst" wird durch „Wo eine Installation stehen darf" ersetzt (Umgebungsanforderungen, Verantwortlichkeit statt Entfernung zum eigenen Serverraum entscheidend, rechtliche Schranke, Erprobung/Schulung ohne echte Daten als Fall). Der vom Projektteam betreute Managed Service entfällt ersatzlos mit Begründung. Kein Anwendungscode geändert, `docker-compose.yml` enthält laut PR keine cloud-spezifischen Reste. Passend zu #348 wird derselbe Nachtrags-Abschnitt in ADR-0014 genutzt (PR weist auf möglichen kleinen Merge-Konflikt mit parallelen PRs hin).

**Verifikation:** `docs/features/deployment-infrastructure.md` enthält die Überschrift „Wo eine Installation stehen darf" (Zeile 263) — der beschriebene Umbau ist im aktuellen Dokument vorhanden.

**Themen:** doku, deployment, architektur, produktvision
