{{- define "relay.labels" -}}
app.kubernetes.io/name: relay-aegis
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/* Reuses a generated secret value across upgrades, so an upgrade never logs everyone out or locks out the database. */}}
{{- define "relay.secretValue" -}}
{{- $existing := lookup "v1" "Secret" .root.Release.Namespace (printf "%s-secrets" .root.Release.Name) -}}
{{- if .value -}}
{{- .value | b64enc -}}
{{- else if and $existing (index $existing.data .key) -}}
{{- index $existing.data .key -}}
{{- else -}}
{{- randAlphaNum 48 | b64enc -}}
{{- end -}}
{{- end }}
