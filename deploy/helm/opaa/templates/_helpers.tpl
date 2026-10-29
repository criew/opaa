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
{{- if or .Values.extraCACertificates.configMap .Values.extraCACertificates.secret }}true{{ end }}
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
{{- $replicas := int .Values.backend.replicas }}
{{- $rolling := include "opaa.backend.rolling" . }}
{{- if or (gt $replicas 1) $rolling }}
{{- $reason := ternary (printf "backend.replicas is %d" $replicas) "backend.updateStrategy is RollingUpdate" (gt $replicas 1) }}
{{- if not .Values.backend.migration.enabled }}
{{- fail (printf "%s, which runs two backend pods side by side, so the schema migration must run as a step of its own before them: set backend.migration.enabled (ADR-0053, Entscheidung 15)." $reason) }}
{{- end }}
{{- if not (include "opaa.uploads.shared" .) }}
{{- fail (printf "%s, which runs two backend pods side by side, possibly on different nodes, so every pod must see the same originals: set uploads.store s3, or uploads.persistence.accessModes [ReadWriteMany] on a storage class that provides it (ADR-0053, Entscheidung 15)." $reason) }}
{{- end }}
{{- end }}
{{- $shutdown := int (include "opaa.backend.shutdownTimeoutSeconds" .) }}
{{- $preStop := int (include "opaa.backend.preStopSeconds" .) }}
{{- $grace := int (include "opaa.backend.terminationGracePeriodSeconds" .) }}
{{- if le $grace (add $shutdown $preStop) }}
{{- fail (printf "backend.terminationGracePeriodSeconds (%d) must be greater than backend.shutdownTimeoutSeconds (%d) plus the preStop pause (%d), otherwise the kubelet kills the backend before its graceful shutdown ends." $grace $shutdown $preStop) }}
{{- end }}
{{- $readTimeout := int (include "opaa.frontend.readTimeoutSeconds" .) }}
{{- if and $rolling (lt $shutdown $readTimeout) }}
{{- fail (printf "backend.shutdownTimeoutSeconds (%d) is shorter than the frontend's read timeout (%ds, frontend.backendReadTimeout): a rolling update would cut off chat answers still in progress on the replaced pod. Raise it, or leave it empty to follow the read timeout." $shutdown $readTimeout) }}
{{- end }}
{{- if and .Values.transcription.maxConcurrent (hasKey .Values.backend.installationBudgets "OPAA_TRANSCRIPTION_MAX_CONCURRENT") }}
{{- fail "transcription.maxConcurrent and backend.installationBudgets.OPAA_TRANSCRIPTION_MAX_CONCURRENT set the same variable; keep one: the budget of the installation, which the chart divides by the pods, or the value per pod." }}
{{- end }}
{{- range .Values.backend.extraEnv }}
{{- if hasKey $.Values.backend.installationBudgets .name }}
{{- fail (printf "backend.extraEnv sets %s, which backend.installationBudgets already derives per pod; keep one of the two." .name) }}
{{- end }}
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
{{- $mountPaths := list }}
{{- $sourceNames := list }}
{{- range .Values.filesystemSources }}
{{- if has .name $sourceNames }}
{{- fail (printf "filesystemSources names %q twice." .name) }}
{{- end }}
{{- $sourceNames = append $sourceNames .name }}
{{- $path := trimSuffix "/" .mountPath }}
{{- if has $path $mountPaths }}
{{- fail (printf "filesystemSources mounts %s twice." $path) }}
{{- end }}
{{- $mountPaths = append $mountPaths $path }}
{{- range list "/tmp" "/app" "/etc/opaa" "/opt/java" }}
{{- if or (eq $path .) (hasPrefix (printf "%s/" .) $path) }}
{{- fail (printf "filesystemSources mount path %s lies in %s, which the backend uses itself." $path .) }}
{{- end }}
{{- end }}
{{- end }}
{{- if .Values.trustedProxyCidrs }}
{{- if not (and .Values.networkPolicy.backendIngress.enabled (include "opaa.frontendIngress.enabled" .)) }}
{{- fail "trustedProxyCidrs is set, but not both ingress policies are on: every pod in the trusted network could then forge X-Forwarded-For. Keep networkPolicy.backendIngress.enabled and set networkPolicy.frontendIngress.controllerNamespaceSelector and controllerPodSelector, each with matchLabels or matchExpressions (ADR-0042, Entscheidung 4)." }}
{{- end }}
{{- range .Values.networkPolicy.backendIngress.extraFrom }}
{{- if not (include "opaa.selectorSet" .podSelector) }}
{{- fail "trustedProxyCidrs is set, so every peer of networkPolicy.backendIngress.extraFrom counts as a trusted proxy and needs a podSelector with matchLabels or matchExpressions." }}
{{- end }}
{{- end }}
{{- end }}
{{- if and .Values.metrics.prometheusRule.enabled (not .Values.metrics.serviceMonitor.enabled) }}
{{- fail "metrics.prometheusRule needs metrics.serviceMonitor: its alerts select the series by the service label the ServiceMonitor sets." }}
{{- end }}
{{- if and .Values.ingress.enabled (not .Values.ingress.host) }}
{{- fail "ingress.enabled is true, but ingress.host is empty." }}
{{- end }}
{{- if and .Values.httpRoute.enabled (not .Values.httpRoute.parentRefs) }}
{{- fail "httpRoute.enabled is true, but httpRoute.parentRefs names no Gateway." }}
{{- end }}
{{- end }}

{{/* "true" when the backend is updated pod by pod; empty for Recreate. */}}
{{- define "opaa.backend.rolling" -}}
{{- $strategy := .Values.backend.updateStrategy }}
{{- if or (eq $strategy "RollingUpdate") (and (not $strategy) (gt (int .Values.backend.replicas) 1)) }}true{{ end }}
{{- end }}

{{/* "true" when every backend pod sees the same originals: object store or a ReadWriteMany claim. */}}
{{- define "opaa.uploads.shared" -}}
{{- if or (eq .Values.uploads.store "s3") (has "ReadWriteMany" .Values.uploads.persistence.accessModes) }}true{{ end }}
{{- end }}

{{/* frontend.backendReadTimeout in seconds; the image default is 600s. */}}
{{- define "opaa.frontend.readTimeoutSeconds" -}}
{{- $value := default "600s" .Values.frontend.backendReadTimeout }}
{{- $number := int (trimSuffix "s" (trimSuffix "m" (trimSuffix "h" $value))) }}
{{- if hasSuffix "h" $value }}{{ mul $number 3600 }}{{ else if hasSuffix "m" $value }}{{ mul $number 60 }}{{ else }}{{ $number }}{{ end }}
{{- end }}

{{/* OPAA_SHUTDOWN_TIMEOUT in seconds: the value, or 60 with Recreate and the frontend's read timeout pod by pod. */}}
{{- define "opaa.backend.shutdownTimeoutSeconds" -}}
{{- if kindIs "invalid" .Values.backend.shutdownTimeoutSeconds }}
{{- if include "opaa.backend.rolling" . }}{{ include "opaa.frontend.readTimeoutSeconds" . }}{{ else }}60{{ end }}
{{- else }}
{{- int .Values.backend.shutdownTimeoutSeconds }}
{{- end }}
{{- end }}

{{/* Seconds of the preStop pause: only pod by pod, where the endpoints must drop the pod first. */}}
{{- define "opaa.backend.preStopSeconds" -}}
{{- if include "opaa.backend.rolling" . }}{{ int .Values.backend.preStopSleepSeconds }}{{ else }}0{{ end }}
{{- end }}

{{/* terminationGracePeriodSeconds: the value, or the shutdown window plus the preStop pause plus 15 s. */}}
{{- define "opaa.backend.terminationGracePeriodSeconds" -}}
{{- if kindIs "invalid" .Values.backend.terminationGracePeriodSeconds }}
{{- add (include "opaa.backend.shutdownTimeoutSeconds" .) (include "opaa.backend.preStopSeconds" .) 15 }}
{{- else }}
{{- int .Values.backend.terminationGracePeriodSeconds }}
{{- end }}
{{- end }}

{{/*
Name of the migration job: changes with image and configuration, so an upgrade that brings either
starts a new job, and stays the same otherwise. At most 63 characters, the limit of its pod labels.
*/}}
{{- define "opaa.migration.jobName" -}}
{{- $inputs := list (include "opaa.image" (dict "root" . "image" .Values.backend.image)) (include (print .Template.BasePath "/backend-configmap.yaml") .) (toYaml .Values.backend.extraEnv) (toYaml .Values.backend.extraEnvFrom) .Chart.Version }}
{{- printf "%s-migrate-%s" (include "opaa.fullname" . | trunc 43 | trimSuffix "-") (join "\n" $inputs | sha256sum | trunc 10) }}
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

{{/*
Comma-separated allowlist: the entries of "hosts" plus host:port of every address in "urls" not yet
listed, so an address named in the values passes its address check without admitting the host's
other ports.
*/}}
{{- define "opaa.allowlistWithAddresses" -}}
{{- $hosts := .hosts | default list }}
{{- range .urls }}
{{- with . }}
{{- $url := urlParse (trim .) }}
{{- if $url.hostname }}
{{- $port := regexFind "[0-9]+$" (regexFind ":[0-9]+$" $url.host) | default (ternary "443" "80" (eq (lower $url.scheme) "https")) }}
{{- $name := ternary (printf "[%s]" $url.hostname) $url.hostname (contains ":" $url.hostname) }}
{{- $entry := printf "%s:%s" $name $port }}
{{- if not (has $entry $hosts) }}
{{- $hosts = append $hosts $entry }}
{{- end }}
{{- end }}
{{- end }}
{{- end }}
{{- join "," $hosts }}
{{- end }}

{{/*
OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST: targetValidation.identityProviderAllowlist plus host and port
of bootstrap.oidc, so the provider of the answer file passes the address check at installation and
at every later use (ADR-0060, Entscheidung 9).
*/}}
{{- define "opaa.identityProviderAllowlist" -}}
{{- include "opaa.allowlistWithAddresses" (dict "hosts" .Values.targetValidation.identityProviderAllowlist "urls" (list .Values.bootstrap.oidc.issuerUri .Values.bootstrap.oidc.jwkSetUri)) }}
{{- end }}

{{/*
OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST: targetValidation.modelAllowlist plus host and port of the
chat and embedding model of the answer file, so they pass the address check of models.
*/}}
{{- define "opaa.modelAllowlist" -}}
{{- include "opaa.allowlistWithAddresses" (dict "hosts" .Values.targetValidation.modelAllowlist "urls" (list .Values.bootstrap.chatModel.baseUrl .Values.embedding.baseUrl)) }}
{{- end }}

{{/*
OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST: installationMailTargetAllowlist plus host:port of
bootstrap.mail, so the mail server of the answer file passes the address check of the installation.
*/}}
{{- define "opaa.installationMailTargetAllowlist" -}}
{{- $hosts := .Values.installationMailTargetAllowlist | default list }}
{{- with .Values.bootstrap.mail }}
{{- if and .host (include "opaa.installation.answerFileEnabled" $) }}
{{- $name := ternary (printf "[%s]" .host) .host (contains ":" .host) }}
{{- $entry := printf "%s:%d" $name (int (.port | default 587)) }}
{{- if not (has $entry $hosts) }}
{{- $hosts = append $hosts $entry }}
{{- end }}
{{- end }}
{{- end }}
{{- join "," $hosts }}
{{- end }}

{{/* "true" when the chart renders an answer file: bootstrap.initialAdmin.email is set. */}}
{{- define "opaa.installation.answerFileEnabled" -}}
{{- if .Values.bootstrap.initialAdmin.email }}true{{ end }}
{{- end }}

{{/* "true" when the answer file names a key file for the chat model. */}}
{{- define "opaa.installation.chatApiKey" -}}
{{- if or .Values.bootstrap.chatModel.apiKeyInSecret (and (not .Values.secrets.existingSecret) .Values.secrets.chatApiKey) }}true{{ end }}
{{- end }}

{{/* "true" when the answer file names a key file for the embedding model. */}}
{{- define "opaa.installation.embeddingApiKey" -}}
{{- if or .Values.embedding.apiKeyInSecret (and (not .Values.secrets.existingSecret) .Values.secrets.embeddingApiKey) }}true{{ end }}
{{- end }}

{{/* "true" when the answer file names a password file for the mail server. */}}
{{- define "opaa.installation.mailPassword" -}}
{{- if and .Values.bootstrap.mail.host (or .Values.bootstrap.mail.passwordInSecret (and (not .Values.secrets.existingSecret) .Values.secrets.mailPassword)) }}true{{ end }}
{{- end }}

{{/* Mount path of the secret files the answer file points to. */}}
{{- define "opaa.installation.secretsPath" -}}
/etc/opaa/installation-secrets
{{- end }}

{{/*
The answer file (ADR-0060, Entscheidung 7) from bootstrap and embedding: first account, sign-in,
chat and embedding model, and the mail server if bootstrap.mail.host is set. Passwords and keys
are never part of it, only paths of files mounted from the secret; the first account changes its password at the first sign-in unless told otherwise.
*/}}
{{- define "opaa.installation.answerFile" -}}
{{- $b := .Values.bootstrap }}
{{- $secrets := include "opaa.installation.secretsPath" . }}
{{- $admin := dict "email" $b.initialAdmin.email "displayName" ($b.initialAdmin.displayName | default "Systemverwaltung") "passwordFile" (printf "%s/admin-password" $secrets) "requirePasswordChange" (ne $b.initialAdmin.requirePasswordChange false) }}
{{- $signIn := dict "mode" "LOCAL_ONLY" }}
{{- with $b.oidc }}
{{- if .issuerUri }}
{{- $oidc := dict "issuerUri" .issuerUri "clientId" (required "bootstrap.oidc.clientId is required with bootstrap.oidc.issuerUri" .clientId) }}
{{- with .jwkSetUri }}{{ $_ := set $oidc "jwkSetUri" . }}{{ end }}
{{- with .displayName }}{{ $_ := set $oidc "displayName" . }}{{ end }}
{{- $signIn = dict "mode" "LOCAL_AND_OIDC" "oidc" $oidc }}
{{- end }}
{{- end }}
{{- $chat := dict "baseUrl" (required "bootstrap.chatModel.baseUrl is required with bootstrap.initialAdmin.email" $b.chatModel.baseUrl) "model" (required "bootstrap.chatModel.model is required with bootstrap.initialAdmin.email" $b.chatModel.model) }}
{{- if include "opaa.installation.chatApiKey" . }}{{ $_ := set $chat "apiKeyFile" (printf "%s/chat-api-key" $secrets) }}{{ end }}
{{- if not (kindIs "invalid" $b.chatModel.temperature) }}{{ $_ := set $chat "temperature" $b.chatModel.temperature }}{{ end }}
{{- with $b.chatModel.maxTokens }}{{ $_ := set $chat "maxTokens" (int .) }}{{ end }}
{{- $e := .Values.embedding }}
{{- $embedding := dict "baseUrl" (required "embedding.baseUrl is required with bootstrap.initialAdmin.email" $e.baseUrl) "model" (required "embedding.model is required with bootstrap.initialAdmin.email" $e.model) }}
{{- if include "opaa.installation.embeddingApiKey" . }}{{ $_ := set $embedding "apiKeyFile" (printf "%s/embedding-api-key" $secrets) }}{{ end }}
{{- with $e.maxInputTokens }}{{ $_ := set $embedding "maxInputTokens" (int .) }}{{ end }}
{{- $answers := dict "firstAdmin" $admin "signIn" $signIn "chatModel" $chat "embeddingModel" $embedding }}
{{- with $b.mail }}
{{- if .host }}
{{- $mail := dict "host" .host "port" (int (.port | default 587)) "encryption" (.encryption | default "STARTTLS") "fromAddress" (required "bootstrap.mail.fromAddress is required with bootstrap.mail.host" .fromAddress) }}
{{- with .username }}{{ $_ := set $mail "username" . }}{{ end }}
{{- with .fromName }}{{ $_ := set $mail "fromName" . }}{{ end }}
{{- if include "opaa.installation.mailPassword" $ }}{{ $_ := set $mail "passwordFile" (printf "%s/mail-password" $secrets) }}{{ end }}
{{- $_ := set $answers "mail" $mail }}
{{- end }}
{{- end }}
{{- $answers | toPrettyJson }}
{{- end }}


{{/* "true" when a label selector selects by something; an empty selector matches everything. */}}
{{- define "opaa.selectorSet" -}}
{{- if and . (or .matchLabels .matchExpressions) }}true{{ end }}
{{- end }}

{{/* "true" when the frontend ingress policy is rendered: controller namespace and pods are named. */}}
{{- define "opaa.frontendIngress.enabled" -}}
{{- with .Values.networkPolicy.frontendIngress }}
{{- if and (include "opaa.selectorSet" .controllerNamespaceSelector) (include "opaa.selectorSet" .controllerPodSelector) }}true{{ end }}
{{- end }}
{{- end }}

{{/* Normalized origin of a URL: lower case, without path and without the scheme's default port. */}}
{{- define "opaa.origin" -}}
{{- $url := urlParse (lower (trim .)) }}
{{- $host := $url.host }}
{{- if eq $url.scheme "https" }}{{ $host = trimSuffix ":443" $host }}{{ end }}
{{- if eq $url.scheme "http" }}{{ $host = trimSuffix ":80" $host }}{{ end }}
{{- printf "%s://%s" $url.scheme $host }}
{{- end }}

{{/*
"true" when a connect-src source list admits the URL .url on a page served from .page, after the
CSP Level 3 rules for host and scheme sources: a source without scheme takes the page's, http admits
https, "*." matches subdomains but not the domain itself, a missing port means the default port, a
path ending in "/" is a prefix and any other path must match exactly (paths are case-sensitive).
.sources is space-separated; keywords other than 'self' admit nothing. Known limits, all erring
towards "not admitted": ports with leading zeros, the upgrade of a source port 80 to 443, a page
on an explicit default port, IPv6 literals, percent-encoded paths.
*/}}
{{- define "opaa.cspAdmits" -}}
{{- $url := urlParse (lower (trim .url)) }}
{{- $urlPath := (urlParse (trim .url)).path }}
{{- $page := urlParse (lower (trim .page)) }}
{{- $defaultPorts := dict "http" "80" "https" "443" "ws" "80" "wss" "443" }}
{{- $host := regexReplaceAll ":[0-9]*$" $url.host "" }}
{{- $port := trimPrefix ":" (regexFind ":[0-9]+$" $url.host) }}
{{- if eq $port (get $defaultPorts $url.scheme) }}{{ $port = "" }}{{ end }}
{{- /* groups: 2 scheme, 3 host with optional leading "*.", 7 port, 8 path */}}
{{- $hostSource := `^(?i)(([a-z][a-z0-9+.-]*)://)?(\*|(\*\.)?[a-z0-9-]+(\.[a-z0-9-]+)*)(:(\*|[0-9]+))?(/.*)?$` }}
{{- $admitted := false }}
{{- range splitList " " .sources }}
{{- $sourcePath := "" }}
{{- if regexMatch $hostSource . }}{{ $sourcePath = regexReplaceAll $hostSource . "${8}" }}{{ end }}
{{- $source := lower . }}
{{- if eq $source "'self'" }}{{ $source = printf "%s://%s" $page.scheme $page.host }}{{ end }}
{{- if eq $source "*" }}
{{- if has $url.scheme (list "http" "https" $page.scheme) }}{{ $admitted = true }}{{ end }}
{{- else if regexMatch "^[a-z][a-z0-9+.-]*:$" $source }}
{{- if include "opaa.cspSchemeMatches" (list (trimSuffix ":" $source) $url.scheme) }}{{ $admitted = true }}{{ end }}
{{- else if regexMatch $hostSource $source }}
{{- $scheme := regexReplaceAll $hostSource $source "${2}" | default $page.scheme }}
{{- $pattern := regexReplaceAll $hostSource $source "${3}" }}
{{- $sourcePort := regexReplaceAll $hostSource $source "${7}" }}
{{- $hostMatches := or (eq $pattern "*" $host) (and (hasPrefix "*." $pattern) (hasSuffix (trimPrefix "*" $pattern) $host)) }}
{{- $portMatches := or (eq $sourcePort "*" $port) (and (not $port) (eq $sourcePort (get $defaultPorts $url.scheme))) }}
{{- /* a prefix ending in "/" can only end on a segment boundary of the URL's path */}}
{{- $pathMatches := or (not $sourcePath) (eq $sourcePath $urlPath) (and (hasSuffix "/" $sourcePath) (hasPrefix $sourcePath $urlPath)) }}
{{- if and $hostMatches $portMatches $pathMatches (include "opaa.cspSchemeMatches" (list $scheme $url.scheme)) }}{{ $admitted = true }}{{ end }}
{{- end }}
{{- end }}
{{- if $admitted }}true{{ end }}
{{- end }}

{{/* "true" when the scheme of a CSP source (first) admits a URL's scheme (second); http admits https. */}}
{{- define "opaa.cspSchemeMatches" -}}
{{- $source := index . 0 }}
{{- $scheme := index . 1 }}
{{- if or (eq $source $scheme) (and (eq $source "http") (eq $scheme "https")) (and (eq $source "ws") (has $scheme (list "wss" "http" "https"))) (and (eq $source "wss") (eq $scheme "https")) }}true{{ end }}
{{- end }}
