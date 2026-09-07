#!/usr/bin/env bash
set -euo pipefail

while IFS= read -r -d '' first; do
  prefix="${first%.part.001}"
  destination="${prefix#.ci-source/}"
  mkdir -p "$(dirname "$destination")"
  mapfile -t parts < <(printf '%s\n' "${prefix}".part.* | sort)
  test "${#parts[@]}" -gt 0
  cat "${parts[@]}" > "$destination"
done < <(find .ci-source -type f -name '*.part.001' -print0)

if [[ -f .ci-source/expected-git-blobs.txt ]]; then
  while read -r expected path; do
    [[ -z "${expected:-}" || "${expected:0:1}" == "#" ]] && continue
    actual=$(git hash-object "$path")
    if [[ "$actual" != "$expected" ]]; then
      echo "Source mismatch: $path expected=$expected actual=$actual" >&2
      exit 1
    fi
  done < .ci-source/expected-git-blobs.txt
fi
