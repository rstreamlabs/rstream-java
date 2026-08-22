#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <version> <central-bundle.zip>" >&2
  exit 1
fi

version=$1
bundle=$2
staging_root="target/central-bundle-staging"
destination="${staging_root}/io/rstream/rstream/${version}"

if [[ ! "$version" =~ ^[0-9]+(\.[0-9]+){2}$ ]]; then
  echo "invalid release version: ${version}" >&2
  exit 1
fi
if [[ -e "$staging_root" || -e "$bundle" ]]; then
  echo "Maven Central bundle output already exists" >&2
  exit 1
fi

mkdir -p "$destination" "$(dirname "$bundle")"
artifacts=(
  "target/rstream-${version}.pom"
  "target/rstream-${version}.jar"
  "target/rstream-${version}-sources.jar"
  "target/rstream-${version}-javadoc.jar"
)
for artifact in "${artifacts[@]}"; do
  for source in "$artifact" "${artifact}.asc"; do
    if [[ ! -f "$source" ]]; then
      echo "signed Maven artifact is missing: ${source}" >&2
      exit 1
    fi
    cp "$source" "$destination/"
  done
done

for artifact in "$destination"/*; do
  md5sum "$artifact" | awk '{print $1}' > "${artifact}.md5"
  sha1sum "$artifact" | awk '{print $1}' > "${artifact}.sha1"
  sha256sum "$artifact" | awk '{print $1}' > "${artifact}.sha256"
  sha512sum "$artifact" | awk '{print $1}' > "${artifact}.sha512"
done

bundle=$(cd "$(dirname "$bundle")" && pwd)/$(basename "$bundle")
(
  cd "$staging_root"
  find io -type f -print | LC_ALL=C sort | zip -q "$bundle" -@
)
