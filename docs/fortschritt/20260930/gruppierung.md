# Gruppierung der Bausteine nach Themenbereichen — Zeitraum 31.08.–30.09.2026

Gruppiert wird wie am [Vorgänger-Stichtag](../20260831/gruppierung.md) entlang der **elf
Themenbereiche A–K der [Produktvision](../../VISION.md)**, ergänzt um Produktvision (V), Projekt als
Produkt (P), Projektsetup (T1), Agenten-Organisation (T2) und Testinfrastruktur (T3).

Jeder der 616 geprüften Vorgänge ist genau **einem** Bereich zugeordnet. `#N` = Issue, `PR#N` =
PR ohne Issue-Verknüpfung. Befunde stehen in [bausteine.md](./bausteine.md).

**Regel für den Report:** Der Report zeigt nur den **Endzustand** des Zeitraums. Was im Zeitraum
gebaut und wieder ersetzt wurde, ist dort kein Leistungsposten. Das betrifft den Volltext-Nachzug
(#1047, #1093, #1170), die Verteilungsstufe `visibility` (#1496, #1931), die Ansprechstellen an
Anbietergruppen (#1875), die Confluence-Sonderspalten (PR#1174), die zweite Liquibase-Baseline
(#1492) und den MinIO-Testbetrieb (#1578, #1948).

---

## A · Wissensschicht & Retrieval

Hybride Suche (Epic #1045): Stufen-Pipeline mit Erklärprotokoll, lexikalischer Volltextpfad in
der RRF-Fusion, Reranking als Modellrolle, Diagnose je Stufe auch im Rechtekontext einer Person.
Metadatenschema (Epic #1065) samt Nachkalibrierung (#1364) und hartem Filter in beiden Suchpfaden.
Gesprächsgedächtnis (Epic #1482) mit Mehrrunden-Messpfad. Ausbau des Suchqualitäts-Benchmarks
(Epic #1036: Pipeline-Messpfad, Varianten, Verwaltungsdomäne „Kalkstadt“) und die laufende
Pflege der Baselines, einschließlich der automatisch geöffneten und wieder geschlossenen
Regressionsalarme.

#1036, #1039, #1040, #1041, #1042, #1043, #1044, #1045, #1046, #1047, #1048, #1049, #1050,
#1051, #1053, #1061, #1065, #1066, #1067, #1068, #1069, #1070, #1071, #1072, #1073, #1076,
#1081, #1085, #1093, #1102, #1103, #1119, #1120, #1144, #1150, #1151, #1153, #1154, #1160,
#1164, #1170, #1198, #1207, #1209, #1210, #1211, #1222, #1230, #1242, #1254, #1257, #1263,
#1270, #1288, #1289, #1305, #1307, #1308, #1317, #1318, #1346, #1360, #1361, #1364, #1429,
#1446, #1460, #1482, #1483, #1484, #1485, #1486, #1487, #1490, #1501, #1522, #1553, #1586,
#1587, #1598, #1635, #1650, #1652, #1655, #1657, #1658, #1671, #1674, #1684, #1839, #1840,
#1841, #1842, PR#1159, PR#1166, PR#1298, PR#1358, PR#1365, PR#1430, PR#1651, PR#1670, PR#1672

(102 Vorgänge, davon 14 mit Befund)

---

## B · Wissensquellen & Konnektoren

Ingestion-Pipelines je Dokumenttyp (Epic #1054), Anhänge als eigene Dokumente (ADR-0022, Epic
#1178), Confluence-Konnektor (Epic #1129), S3-Konnektor (Epic #1291), Konsolidierung und
Neugliederung der Ingestion (Epics #1316, #1401, #1415), steckbare Konnektoren (ADR-0038),
Crawler-Härtung und die Handbuchkapitel zu Konnektoren und Formaten.

#1054, #1055, #1056, #1057, #1058, #1059, #1060, #1105, #1107, #1108, #1110, #1126, #1129,
#1130, #1131, #1132, #1133, #1134, #1135, #1136, #1137, #1138, #1139, #1140, #1141, #1142,
#1145, #1162, #1167, #1171, #1178, #1180, #1181, #1182, #1183, #1184, #1191, #1200, #1215,
#1218, #1219, #1229, #1236, #1239, #1243, #1268, #1269, #1271, #1273, #1277, #1287, #1291,
#1301, #1309, #1310, #1311, #1312, #1313, #1314, #1315, #1316, #1337, #1338, #1354, #1357,
#1373, #1374, #1375, #1376, #1377, #1378, #1379, #1380, #1381, #1383, #1396, #1397, #1398,
#1399, #1401, #1415, #1416, #1417, #1418, #1421, #1524, #1804, #1805, #1806, #1976, #1977,
#2031, #2033, PR#1111, PR#1163, PR#1173, PR#1174, PR#1175, PR#1176, PR#1179, PR#1185, PR#1192,
PR#1199, PR#1202, PR#1205, PR#1208, PR#1283, PR#1304, PR#1530, PR#1983, PR#1986

(111 Vorgänge, davon 4 mit Befund)

---

## C · Spaces, Assets & Verteilung

Gemeinsame Asset-Schale für mehrere Asset-Typen, Space-Zuordnung auf der Schale,
organisationsweiter Katalog, Reichweite als Freigabe an „Alle Konten“ (ADR-0037) statt eigener
Verteilungsstufe.

#797, #1726, #1899, #1900, #1904, #1931

(6 Vorgänge, davon 1 mit Befund)

---

## D · Agenten, Prompts & Werkzeuge

Prompt-Bibliothek als zweiter Asset-Typ (Epic #1726) mit Oberfläche und Slash-Befehl im Chat;
Spike einer Werkzeugschleife hinter Schalter. Die Spezifikationsarbeit zu Agenten steht unter V.

#1789, #1901, #1902, #1903

(4 Vorgänge, davon 0 mit Befund)

---

## E · Modelle & zentrale Steuerung

Reranking als Modellrolle ist unter A geführt, weil es als Retrieval-Stufe geliefert wurde.

*Kein Vorgang im Zeitraum.*

---

## F · Identität, Rechte & Mandanten

Mehranbieter-OIDC (Epic #1294), lokale Benutzerverwaltung (Epic #1529), Berechtigungsmodell
mit Gruppen und Fähigkeiten (Epic #1295, ADR-0036) einschließlich Verzeichnisabgleich mit Keycloak
als erstem Konnektor, Kontostatus, Nachfolge, Rechteübertragung, Herleitung und Stichtagsauskunft;
Härtung der Rechtehistorie.

#1294, #1295, #1327, #1329, #1330, #1331, #1332, #1333, #1334, #1349, #1368, #1369, #1428,
#1443, #1496, #1497, #1500, #1505, #1517, #1529, #1531, #1532, #1533, #1534, #1536, #1537,
#1538, #1539, #1540, #1541, #1542, #1543, #1552, #1556, #1563, #1592, #1594, #1595, #1601,
#1603, #1612, #1629, #1630, #1631, #1640, #1641, #1685, #1689, #1697, #1705, #1711, #1807,
#1808, #1809, #1810, #1811, #1812, #1813, #1814, #1815, #1816, #1817, #1818, #1819, #1820,
#1821, #1822, #1824, #1828, #1830, #1832, #1834, #1835, #1856, #1875, #1879, #1880, #1978,
#1992, #2037, PR#1545, PR#1560, PR#1873

(83 Vorgänge, davon 12 mit Befund)

---

## G · Sicherheit, Nachweis & Prüfbarkeit

Befugnis- und Protokollmodell der Diagnose „Sicht als“, SBOM und CVE-Erkennung, Triage und
Härtung der Laufzeit-Images (Distroless, Nicht-root, wöchentlicher Neubau, Scan-Alarm),
Ratenbegrenzung hinter Proxys, Aufbewahrungsfristen und Löschläufe für Protokolle und
Rechtehistorie.

#1052, #1078, #1079, #1124, #1127, #1147, #1256, #1259, #1345, #1431, #1450, #1456, #1459,
#1461, #1464, #1466, #1471, #1509, #1535, #1833, #1850, #1851, #1989, PR#1791

(24 Vorgänge, davon 3 mit Befund)

---

## H · Monitoring, Kosten & Governance

*Kein Vorgang im Zeitraum.*

---

## I · Kanäle & Oberflächen

Fremdzugänge (Epic #1715: persönliche Zugangstokens, Such-Endpunkt, MCP-Server), Chatliste
(Epic #1762), Detailansicht der Wissensbibliothek (Epic #1927), zwei UI-Politur-Runden
(Verwaltungsbereich, Anmeldung und Übersichten), Kopierfunktion und Belegansicht im Chat sowie
das vereinheitlichte Fehlerverhalten der REST-API.

#1238, #1264, #1267, #1447, #1449, #1488, #1574, #1600, #1604, #1607, #1608, #1609, #1610,
#1614, #1616, #1617, #1619, #1621, #1623, #1625, #1627, #1647, #1707, #1715, #1716, #1717,
#1718, #1719, #1720, #1721, #1722, #1731, #1762, #1766, #1768, #1769, #1770, #1771, #1773,
#1780, #1781, #1785, #1786, #1787, #1799, #1910, #1911, #1912, #1913, #1914, #1915, #1916,
#1917, #1918, #1919, #1920, #1921, #1922, #1924, #1927, #1939, #1940, #1941, #1942, #1943,
#1944, #1970, #1993, #2062, #2064, PR#1605, PR#1611, PR#1615, PR#1618, PR#1620, PR#1622,
PR#1624, PR#1626, PR#1628, PR#1633, PR#1688, PR#1765

(82 Vorgänge, davon 1 mit Befund)

---

## J · Betrieb & Deployment

Originalablage der Uploads im Dateisystem oder Objektspeicher (Epic #1440, ADR-0030),
konfigurierbares Datenbankschema, sanftes Herunterfahren, Flyer mit Installationsvoraussetzungen,
Ausbau und Neuaufsatz der Demo „Stadt Rheinfurt“ (Epic #2012).

#1366, #1400, #1440, #1474, #1475, #1476, #1477, #1478, #1515, #1518, #1519, #1520, #1525,
#1526, #1544, #1710, #1823, #1895, #2012, #2013, #2014, #2015, #2016, #2017, #2018, #2020,
PR#1709

(27 Vorgänge, davon 1 mit Befund)

---

## K · Verwaltungs-Spezifika

Barrierefreiheits-Nachbesserungen stehen unter I und T3, weil sie an Oberflächen- bzw.
Prüfvorgängen hängen.

*Kein Vorgang im Zeitraum.*

---

## T1 · Projektsetup, Build & Werkzeugkette

Modularisierung des Backends (Epic #1906): Zyklenfreiheit, logische Module per ArchUnit, Liquibase
und OpenAPI je Modul bzw. Thema; Struktur-Review und Entkernung des query-Pakets; CI-Laufzeiten
(Sharding); 54 Renovate-Updates.

#1013, #1089, #1112, #1113, #1117, #1123, #1226, #1371, #1420, #1423, #1424, #1444, #1454,
#1455, #1457, #1458, #1492, #1660, #1708, #1844, #1861, #1906, #1959, #1960, #1961, #1973,
#1974, #1975, #2000, #2001, #2002, #2003, #2004, #2034, #2044, #2045, #2046, #2047, #2052,
#2054, PR#1027, PR#1028, PR#1098, PR#1099, PR#1157, PR#1251, PR#1321, PR#1322, PR#1323, PR#1367,
PR#1409, PR#1411, PR#1433, PR#1434, PR#1435, PR#1437, PR#1438, PR#1453, PR#1511, PR#1512,
PR#1513, PR#1514, PR#1596, PR#1597, PR#1653, PR#1654, PR#1663, PR#1665, PR#1666, PR#1691,
PR#1692, PR#1735, PR#1736, PR#1792, PR#1793, PR#1794, PR#1795, PR#1847, PR#1848, PR#1862,
PR#1891, PR#1897, PR#1898, PR#1908, PR#1909, PR#1956, PR#1957, PR#1965, PR#1966, PR#1967,
PR#1968, PR#1998, PR#1999, PR#2039, PR#2040, PR#2059, PR#2060, PR#2061

(98 Vorgänge, davon 3 mit Befund)

---

## T2 · Agenten-Organisation & Projektsteuerung

Retrospektive-Beschlüsse, Review ab PR-Eröffnung, Epic-Schnittregel, Aufteilung der AGENTS.md je
Modul.

#1223, #1245, #1863, #1929, #2005, PR#1225

(6 Vorgänge, davon 0 mit Befund)

---

## T3 · Testinfrastruktur & E2E

Konsolidierung der Spring-Testkontexte, Testisolation (LeftoverRowGuard, SeededRowRestorer),
Container-Suiten (Confluence, MinIO/RustFS), Struktur-Wächter, E2E-Szenarien der neuen Funktionen
und die automatisch geöffneten und wieder geschlossenen E2E-Alarm-Issues.

#1109, #1152, #1169, #1197, #1261, #1325, #1382, #1414, #1481, #1489, #1495, #1503, #1510,
#1561, #1565, #1573, #1578, #1581, #1582, #1584, #1589, #1606, #1643, #1645, #1649, #1669,
#1723, #1739, #1759, #1776, #1788, #1796, #1801, #1802, #1838, #1852, #1853, #1869, #1876,
#1948, #1949, #1953, #1958, #1971, #1985, #2025, #2026, #2030, #2048, PR#1177, PR#1569, PR#1683,
PR#1996

(53 Vorgänge, davon 4 mit Befund)

---

## V · Produktvision, Strategie & Konzeption

Feature-Spezifikationen (Benchmark, Hybrid-Suche, Ingestion, Metadaten, Fremdzugänge),
geschärfte Vision, Konzeptarbeit zu Agenten und Werkzeugen (agents-and-tools.md), Konzept der
Chatliste.

#1029, #1032, #1033, #1063, #1725, #1728, #1732, #1733, #1745, PR#1724, PR#1742, PR#1757,
PR#1761, PR#1763, PR#1772

(15 Vorgänge, davon 0 mit Befund)

---

## P · Projekt als Produkt: Öffentlichkeit, Demo & Governance

Finalisierung des Meilenstein-1-Berichts, Einstieg ins Produkthandbuch, Demo-Drehbuch.

#945, #1037, #1395, #2019, PR#1436

(5 Vorgänge, davon 0 mit Befund)
