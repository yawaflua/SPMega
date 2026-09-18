#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$script_dir/dist"

case "$(go env GOOS)" in
  windows) output="$script_dir/dist/spmega.dll" ;;
  darwin) output="$script_dir/dist/spmega.dylib" ;;
  *) output="$script_dir/dist/spmega.so" ;;
esac

(cd "$script_dir" && go build -buildmode=c-shared -o "$output" ./app/gbm)
echo "$output"
