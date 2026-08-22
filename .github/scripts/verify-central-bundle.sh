#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <version> <central-bundle.zip>" >&2
  exit 1
fi

version=$1
bundle=$2
prefix="io/rstream/rstream/${version}"

if [[ ! "$version" =~ ^[0-9]+(\.[0-9]+){2}$ ]]; then
  echo "invalid release version: ${version}" >&2
  exit 1
fi
if [[ ! -f "$bundle" ]]; then
  echo "Maven Central bundle is missing: ${bundle}" >&2
  exit 1
fi

unzip -tq "$bundle" >/dev/null
if unzip -Z1 "$bundle" | awk -v prefix="${prefix}/" '
  /^\// || /(^|\/)\.\.($|\/)/ || index($0, prefix) != 1 { invalid = 1 }
  END { exit invalid }
'; then
  :
else
  echo "Maven Central bundle contains an unsafe path" >&2
  exit 1
fi

required=(
  "rstream-${version}.pom"
  "rstream-${version}.jar"
  "rstream-${version}-sources.jar"
  "rstream-${version}-javadoc.jar"
)
entries=$(unzip -Z1 "$bundle")
for filename in "${required[@]}"; do
  for suffix in "" .asc; do
    expected="${prefix}/${filename}${suffix}"
    if ! grep -Fxq "$expected" <<<"$entries"; then
      echo "Maven Central bundle is missing ${expected}" >&2
      exit 1
    fi
  done
done
