#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <version> <central-bundle.zip>" >&2
  exit 1
fi

version=$1
bundle=$2
repository_url="https://repo1.maven.org/maven2/io/rstream/rstream/${version}"
work_directory=$(mktemp -d)
trap 'rm -rf "$work_directory"' EXIT

"$(dirname "$0")/verify-central-bundle.sh" "$version" "$bundle"
unzip -q "$bundle" -d "$work_directory/bundle"
candidate_directory="${work_directory}/bundle/io/rstream/rstream/${version}"

verify_publication() {
  local attempts=$1
  local files=(
    "rstream-${version}.pom"
    "rstream-${version}.pom.asc"
    "rstream-${version}.jar"
    "rstream-${version}.jar.asc"
    "rstream-${version}-sources.jar"
    "rstream-${version}-sources.jar.asc"
    "rstream-${version}-javadoc.jar"
    "rstream-${version}-javadoc.jar.asc"
  )
  local attempt filename
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    local complete=true
    for filename in "${files[@]}"; do
      if ! curl --fail --silent --show-error --location \
        --output "${work_directory}/${filename}" "${repository_url}/${filename}"; then
        complete=false
        break
      fi
      if ! cmp --silent "${candidate_directory}/${filename}" "${work_directory}/${filename}"; then
        echo "published Maven artifact differs from candidate: ${filename}" >&2
        exit 1
      fi
    done
    if [[ "$complete" == true ]]; then
      return 0
    fi
    if ((attempt < attempts)); then
      sleep 10
    fi
  done
  return 1
}

publication_status=$(curl --silent --location --output /dev/null --write-out '%{http_code}' \
  "${repository_url}/rstream-${version}.pom")
if [[ "$publication_status" == 200 ]]; then
  if ! verify_publication 12; then
    echo "existing Maven Central release is incomplete" >&2
    exit 1
  fi
  exit 0
fi
if [[ "$publication_status" != 404 ]]; then
  echo "Maven Central availability check returned HTTP ${publication_status}" >&2
  exit 1
fi

: "${MAVEN_CENTRAL_USERNAME:?MAVEN_CENTRAL_USERNAME is required}"
: "${MAVEN_CENTRAL_PASSWORD:?MAVEN_CENTRAL_PASSWORD is required}"
authorization=$(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')
printf '::add-mask::%s\n' "$authorization"
deployment_id=$(curl --fail --silent --show-error \
  --header "Authorization: Bearer ${authorization}" \
  --form "bundle=@${bundle};type=application/octet-stream" \
  "https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC&name=rstream-${version}")
if [[ ! "$deployment_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
  echo "Maven Central returned an invalid deployment ID" >&2
  exit 1
fi

for _ in {1..90}; do
  status=$(curl --fail --silent --show-error --request POST \
    --header "Authorization: Bearer ${authorization}" \
    "https://central.sonatype.com/api/v1/publisher/status?id=${deployment_id}")
  state=$(jq -r '.deploymentState' <<<"$status")
  case "$state" in
    PUBLISHED)
      break
      ;;
    FAILED)
      jq '.errors' <<<"$status" >&2
      exit 1
      ;;
    PENDING | VALIDATING | VALIDATED | PUBLISHING)
      sleep 10
      ;;
    *)
      echo "unexpected Maven Central deployment state: ${state}" >&2
      exit 1
      ;;
  esac
done
if [[ "$state" != PUBLISHED ]]; then
  echo "Maven Central deployment did not reach PUBLISHED" >&2
  exit 1
fi
if ! verify_publication 60; then
  echo "Maven Central release did not become publicly verifiable" >&2
  exit 1
fi
