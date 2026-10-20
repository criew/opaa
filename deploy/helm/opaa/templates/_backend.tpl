{{/*
Init containers every pod of the backend image runs before it talks to the database, as list items:
the wait for the evaluation database and the truststore with extraCACertificates. Empty without
either.
*/}}
{{- define "opaa.backend.prepareContainers" -}}
{{- if .Values.evaluationDatabase.enabled }}
# Holds the pod back until the evaluation database accepts connections through its service; on the
# first start initdb takes longer than the backend needs to reach the database. Uses the database
# image, whose postgres account (999) satisfies runAsNonRoot.
- name: wait-for-database
  image: {{ include "opaa.image" (dict "root" . "image" .Values.evaluationDatabase.image) | quote }}
  imagePullPolicy: {{ .Values.evaluationDatabase.image.pullPolicy }}
  securityContext:
    {{- toYaml (merge (dict "runAsUser" 999 "runAsGroup" 999) .Values.containerSecurityContext) | nindent 4 }}
  command:
    - sh
    - -c
    - echo "Waiting for $PGHOST:$PGPORT"; until pg_isready --quiet; do sleep 2; done
  env:
    - name: PGHOST
      value: {{ include "opaa.evaluationDatabase.fullname" . | quote }}
    - name: PGPORT
      value: {{ .Values.database.port | quote }}
    - name: PGUSER
      value: {{ .Values.database.username | quote }}
    - name: PGDATABASE
      value: {{ .Values.database.name | quote }}
  resources:
    {{- toYaml .Values.backend.initResources | nindent 4 }}
{{- end }}
{{- if include "opaa.extraCACertificates.enabled" . }}
# Writes the runtime's CAs plus extraCACertificates into a fresh truststore on every start; a main
# class of the backend image, because the image has no shell.
- name: truststore
  image: {{ include "opaa.image" (dict "root" . "image" .Values.backend.image) | quote }}
  imagePullPolicy: {{ .Values.backend.image.pullPolicy }}
  securityContext:
    {{- toYaml .Values.containerSecurityContext | nindent 4 }}
  command:
    - /opt/java/openjdk/bin/java
    - -cp
    - /app/app.jar
    - -Dloader.main=io.opaa.config.TrustStoreBuilder
    - org.springframework.boot.loader.launch.PropertiesLauncher
    - /etc/opaa/truststore/cacerts
    - /etc/opaa/ca-certificates
  resources:
    {{- toYaml .Values.backend.initResources | nindent 4 }}
  volumeMounts:
    - name: truststore
      mountPath: /etc/opaa/truststore
    - name: ca-certificates
      mountPath: /etc/opaa/ca-certificates
      readOnly: true
    - name: tmp
      mountPath: /tmp
{{- end }}
{{- end }}

{{/*
A container of the backend image in a database start mode (OPAA_MODE), as a list item; call with
(dict "root" $ "name" "migrate" "mode" "migrate"). It gets the backend's configuration and of the
secret only the database password.
*/}}
{{- define "opaa.backend.databaseStep" -}}
{{- $root := .root }}
- name: {{ .name }}
  image: {{ include "opaa.image" (dict "root" $root "image" $root.Values.backend.image) | quote }}
  imagePullPolicy: {{ $root.Values.backend.image.pullPolicy }}
  securityContext:
    {{- toYaml $root.Values.containerSecurityContext | nindent 4 }}
  envFrom:
    - configMapRef:
        name: {{ include "opaa.backend.fullname" $root }}
    {{- with $root.Values.backend.extraEnvFrom }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  env:
    - name: OPAA_MODE
      value: {{ .mode | quote }}
    - name: SPRING_PROFILES_ACTIVE
      value: oidc
    - name: TZ
      value: {{ $root.Values.backend.timeZone | quote }}
    {{- with include "opaa.backend.javaToolOptions" $root }}
    - name: JAVA_TOOL_OPTIONS
      value: {{ . | quote }}
    {{- end }}
    - name: OPAA_DB_PASSWORD
      valueFrom:
        secretKeyRef:
          name: {{ include "opaa.secretName" $root }}
          key: OPAA_DB_PASSWORD
    {{- with $root.Values.backend.extraEnv }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  resources:
    {{- toYaml $root.Values.backend.migration.resources | nindent 4 }}
  volumeMounts:
    - name: tmp
      mountPath: /tmp
    {{- if include "opaa.extraCACertificates.enabled" $root }}
    - name: truststore
      mountPath: /etc/opaa/truststore
      readOnly: true
    {{- end }}
{{- end }}

{{/* Volumes of the truststore with extraCACertificates, as list items; empty without them. */}}
{{- define "opaa.backend.truststoreVolumes" -}}
{{- if include "opaa.extraCACertificates.enabled" . }}
- name: truststore
  emptyDir:
    sizeLimit: 16Mi
- name: ca-certificates
  {{- if .Values.extraCACertificates.configMap }}
  configMap:
    name: {{ .Values.extraCACertificates.configMap }}
  {{- else }}
  secret:
    secretName: {{ .Values.extraCACertificates.secret }}
  {{- end }}
{{- end }}
{{- end }}
