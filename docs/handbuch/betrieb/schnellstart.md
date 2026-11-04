# Schnellstart

Sie haben danach eine laufende OPAA-Instanz auf einem Rechner, mit einem Sprachmodell über einen
API-Schlüssel. Das dauert etwa eine Viertelstunde.

**Sie brauchen:** Docker mit Compose, Git und einen API-Schlüssel eines Modellanbieters, etwa
OpenAI. Ein OpenAI-Schlüssel deckt Chat- und Embedding-Modell ab.

## 1. Holen und vorbereiten

```bash
git clone https://github.com/criew/opaa.git && cd opaa
cp .env.docker.example .env.docker
mkdir -p documents uploads
sudo chown -R 65532:65532 uploads
chmod -R o+rX documents
```

In `.env.docker` zwei Zeilen setzen:

```env
SPRING_PROFILES_ACTIVE=docker,oidc
OPAA_AUTH_JWT_SECRET=<Ausgabe von: openssl rand -base64 48>
```

## 2. Starten

```bash
docker compose up -d --build
docker compose logs backend | grep -A3 "INSTALLATION TOKEN"
```

Der zweite Befehl zeigt das einmalige Installations-Token.

## 3. Im Browser einrichten

1. `http://localhost:3000` öffnen. Der Einrichtungs-Assistent erscheint.
2. Token eingeben, erstes Konto anlegen, Anmeldeverfahren „Nur lokale Konten“.
3. **Chat-Modell:** Anbieter wählen, API-Schlüssel eintragen, Modell aus der Liste wählen.
4. **Embedding-Modell:** ebenso. Ohne Embedding-Modell findet die Suche nichts.
5. Den Rest überspringen; alles lässt sich später unter „Administration“ nachholen.

Danach mit dem neuen Konto anmelden und mit den [ersten Schritten](../benutzer/erste-schritte.md)
weitermachen: Bibliothek anlegen, Dokumente hochladen, erste Frage stellen.

**Gut zu wissen:**

- Diese Instanz ist nur vom eigenen Rechner aus erreichbar und nicht gehärtet. Für den Betrieb mit
  echten Nutzern: [Docker Compose](docker-compose.md) oder [Kubernetes](kubernetes.md).
- Alle Wege durch die Einrichtung, auch ohne Browser mit einer Antwortdatei:
  [Installation](installation.md).
- Ganz ohne Cloud-Anbieter laufen die Modelle auch lokal über Ollama
  ([Docker Compose](docker-compose.md#lokal-betriebenes-ollama-im-compose-stack)).
