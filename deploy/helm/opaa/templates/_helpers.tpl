{{/* Chart name, overridable with nameOverride. */}}
{{- define "opaa.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Release-scoped base name of all objects; component objects append -backend/-frontend. */}}
{{- define "opaa.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 50 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 50 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 50 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "opaa.backend.fullname" -}}
{{- printf "%s-backend" (include "opaa.fullname" .) }}
{{- end }}

{{- define "opaa.evaluationDatabase.fullname" -}}
{{- printf "%s-postgresql" (include "opaa.fullname" .) }}
{{- end }}

{{- define "opaa.frontend.fullname" -}}
{{- printf "%s-frontend" (include "opaa.fullname" .) }}
{{- end }}

{{- define "opaa.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "opaa.labels" -}}
helm.sh/chart: {{ include "opaa.chart" . }}
{{ include "opaa.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "opaa.selectorLabels" -}}
app.kubernetes.io/name: {{ include "opaa.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* Selector labels of one component; call with (dict "root" $ "component" "backend"). */}}
{{- define "opaa.componentSelectorLabels" -}}
{{ include "opaa.selectorLabels" .root }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- define "opaa.componentLabels" -}}
{{ include "opaa.labels" .root }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- define "opaa.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "opaa.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/* Image reference; call with (dict "root" $ "image" .Values.backend.image). */}}
{{- define "opaa.image" -}}
{{- if .image.digest }}
{{- printf "%s@%s" .image.repository .image.digest }}
{{- else }}
{{- printf "%s:%s" .image.repository (default .root.Chart.AppVersion .image.tag) }}
{{- end }}
{{- end }}

{{- define "opaa.secretName" -}}
{{- default (include "opaa.fullname" .) .Values.secrets.existingSecret }}
{{- end }}

{{/* Database host: the external one, or the evaluation database of the release. */}}
{{- define "opaa.databaseHost" -}}
{{- if .Values.evaluationDatabase.enabled }}
{{- include "opaa.evaluationDatabase.fullname" . }}
{{- else }}
{{- .Values.database.host }}
{{- end }}
{{- end }}

{{- define "opaa.databaseUrl" -}}
{{- $params := list "prepareThreshold=0" }}
{{- with .Values.database.sslMode }}
{{- $params = append $params (printf "sslmode=%s" .) }}
{{- if hasPrefix "verify-" . }}
{{- /* Checks the server certificate against the Java truststore (public CAs plus extraCACertificates) instead of ~/.postgresql/root.crt. */}}
{{- $params = append $params "sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory" }}
{{- end }}
{{- end }}
{{- printf "jdbc:postgresql://%s:%v/%s?%s" (include "opaa.databaseHost" .) .Values.database.port .Values.database.name (join "&" $params) }}
{{- end }}

{{- define "opaa.uploads.claimName" -}}
{{- default (printf "%s-uploads" (include "opaa.fullname" .)) .Values.uploads.persistence.existingClaim }}
{{- end }}

{{- define "opaa.extraCACertificates.enabled" -}}
{{- if and (or .Values.extraCACertificates.configMap .Values.extraCACertificates.secret) .Values.extraCACertificates.keys }}true{{ end }}
{{- end }}

{{/* host[:port] the frontend nginx forwards /api/ and /mcp to. */}}
{{- define "opaa.backendUpstream" -}}
{{- printf "%s:%v" (include "opaa.backend.fullname" .) .Values.backend.service.port }}
{{- end }}

{{/*
Aborts the rendering on combinations the schema cannot express. Included by both workloads, so the
message names the rule no matter which object Helm renders first.
*/}}
{{- define "opaa.validate" -}}
{{- if gt (int .Values.backend.replicas) 1 }}
{{- fail (printf "backend.replicas is %v, but OPAA runs exactly one backend instance (ADR-0021, docs/decisions/0021-single-instance-betrieb.md). 0 stops the backend, more than 1 is refused." .Values.backend.replicas) }}
{{- end }}
{{- if le (int .Values.backend.terminationGracePeriodSeconds) (int .Values.backend.shutdownTimeoutSeconds) }}
{{- fail (printf "backend.terminationGracePeriodSeconds (%v) must be greater than backend.shutdownTimeoutSeconds (%v), otherwise the kubelet kills the backend before its graceful shutdown ends." .Values.backend.terminationGracePeriodSeconds .Values.backend.shutdownTimeoutSeconds) }}
{{- end }}
{{- range .Values.backend.extraEnv }}
{{- if has .name (list "SPRING_PROFILES_ACTIVE" "SPRING_PROFILES_INCLUDE" "SPRING_PROFILES_DEFAULT") }}
{{- fail (printf "backend.extraEnv must not set %s: the chart runs the production profile oidc only, the dev profile authenticates every request without a credential (ADR-0005)." .name) }}
{{- end }}
{{- if eq .name "JAVA_TOOL_OPTIONS" }}
{{- fail "backend.extraEnv must not set JAVA_TOOL_OPTIONS, it would drop backend.maxRamPercentage; use backend.javaToolOptions instead." }}
{{- end }}
{{- end }}
{{- if and .Values.evaluationDatabase.enabled .Values.database.host }}
{{- fail "database.host and evaluationDatabase.enabled exclude each other: either connect an external database or run the evaluation database." }}
{{- end }}
{{- if and .Values.extraCACertificates.configMap .Values.extraCACertificates.secret }}
{{- fail "extraCACertificates names both a configMap and a secret; choose one." }}
{{- end }}
{{- if and .Values.extraCACertificates.keys (not (or .Values.extraCACertificates.configMap .Values.extraCACertificates.secret)) }}
{{- fail "extraCACertificates.keys is set, but neither extraCACertificates.configMap nor extraCACertificates.secret names their source." }}
{{- end }}
{{- if and .Values.ingress.enabled (not .Values.ingress.host) }}
{{- fail "ingress.enabled is true, but ingress.host is empty." }}
{{- end }}
{{- if and .Values.httpRoute.enabled (not .Values.httpRoute.parentRefs) }}
{{- fail "httpRoute.enabled is true, but httpRoute.parentRefs names no Gateway." }}
{{- end }}
{{- end }}

{{/* JAVA_TOOL_OPTIONS of the backend: the heap share plus the operator's own options. */}}
{{- define "opaa.backend.javaToolOptions" -}}
{{- $options := list }}
{{- with .Values.backend.maxRamPercentage }}
{{- $options = append $options (printf "-XX:MaxRAMPercentage=%v" .) }}
{{- end }}
{{- if include "opaa.extraCACertificates.enabled" . }}
{{- $options = append $options "-Djavax.net.ssl.trustStore=/etc/opaa/truststore/cacerts -Djavax.net.ssl.trustStorePassword=changeit" }}
{{- end }}
{{- with .Values.backend.javaToolOptions }}
{{- $options = append $options . }}
{{- end }}
{{- join " " $options }}
{{- end }}

{{/*
Pod-level fields shared by all workloads; call with (dict "root" $ "component" .Values.backend),
optionally with "podSecurityContext" replacing the chart-wide one.
*/}}
{{- define "opaa.podSpecCommon" -}}
serviceAccountName: {{ include "opaa.serviceAccountName" .root }}
automountServiceAccountToken: false
enableServiceLinks: false
securityContext:
  {{- toYaml (default .root.Values.podSecurityContext .podSecurityContext) | nindent 2 }}
{{- with .root.Values.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .component.nodeSelector }}
nodeSelector:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .component.affinity }}
affinity:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .component.tolerations }}
tolerations:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end }}

{{/* emptyDir for a writable path; call with the component's tmp/uploads value. */}}
{{- define "opaa.emptyDir" -}}
emptyDir:
  {{- with .medium }}
  medium: {{ . }}
  {{- end }}
  {{- with .sizeLimit }}
  sizeLimit: {{ . }}
  {{- end }}
{{- end }}

{{/* Mount paths of filesystemSources, comma-separated: the allowlist of the FILESYSTEM connector. */}}
{{- define "opaa.filesystemAllowlist" -}}
{{- $paths := list }}
{{- range .Values.filesystemSources }}
{{- $paths = append $paths .mountPath }}
{{- end }}
{{- join "," $paths }}
{{- end }}
