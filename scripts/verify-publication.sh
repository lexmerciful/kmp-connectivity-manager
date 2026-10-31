#!/usr/bin/env bash
# Checks what `./gradlew publishToMavenLocal -PVERSION_NAME=<version>` produced against Maven Central's
# requirements, before a real release: every expected artifact, POM metadata, sources/javadoc jars,
# Gradle module metadata, and inter-module dependencies on the same version.
#
# Usage: scripts/verify-publication.sh <version> [--with-ios]
#   --with-ios  also require the iOS artifacts (published from macOS only)
set -euo pipefail

version="${1:?usage: $0 <version> [--with-ios]}"
with_ios="${2:-}"
group_dir="${HOME}/.m2/repository/io/github/lexmerciful"
failures=0

fail() { echo "  ✗ $*"; failures=$((failures + 1)); }
ok() { echo "  ✓ $*"; }

all_targets=(android jvm js wasm-js)
[[ "$with_ios" == "--with-ios" ]] && all_targets+=(iosarm64 iossimulatorarm64)

modules=(connectivity-core connectivity-compose connectivity-ktor connectivity-okhttp)

targets_for() {
  case "$1" in
    connectivity-okhttp) echo "android jvm" ;;   # OkHttp is JVM-only
    *) echo "${all_targets[*]}" ;;
  esac
}

check_pom() {
  local pom="$1" artifact="$2"
  for tag in name description url licenses developers scm; do
    grep -q "<$tag>" "$pom" || fail "$artifact: POM has no <$tag>"
  done
  grep -q "<url>https://github.com/lexmerciful/kmp-connectivity-manager</url>" "$pom" || fail "$artifact: POM url is not the GitHub repo"
  grep -q -- "-SNAPSHOT</version>" "$pom" && fail "$artifact: POM depends on a SNAPSHOT"
  # Sibling modules must be referenced with the released version, not a project path.
  while read -r dep; do
    grep -A1 "<artifactId>$dep</artifactId>" "$pom" | grep -q "<version>$version</version>" ||
      fail "$artifact: dependency $dep is not version $version"
  done < <(grep -o '<artifactId>connectivity-[a-z0-9-]*</artifactId>' "$pom" | sed 's/<[^>]*>//g' | grep -vx "$artifact" || true)
}

check_publication() {
  local artifact="$1" packaging="$2"
  local dir="$group_dir/$artifact/$version"
  if [[ ! -d "$dir" ]]; then
    fail "$artifact: not published ($dir missing)"
    return
  fi
  local base="$dir/$artifact-$version"
  if [[ -f "$base.pom" ]]; then check_pom "$base.pom" "$artifact"; else fail "$artifact: no .pom"; fi
  [[ -f "$base.module" ]] || fail "$artifact: no Gradle module metadata (.module)"
  [[ -f "$base-sources.jar" ]] || fail "$artifact: no -sources.jar"
  [[ -f "$base-javadoc.jar" ]] || fail "$artifact: no -javadoc.jar"
  if [[ -n "$packaging" && ! -f "$base.$packaging" ]]; then
    fail "$artifact: no .$packaging"
  fi
  ok "$artifact"
}

packaging_for() {
  case "$1" in
    android) echo aar ;;
    jvm) echo jar ;;
    *) echo klib ;;
  esac
}

for module in "${modules[@]}"; do
  echo "$module"
  check_publication "$module" ""   # root (multiplatform) publication: metadata only
  for target in $(targets_for "$module"); do
    check_publication "$module-$target" "$(packaging_for "$target")"
  done
done

# The javadoc jar must hold real API docs, not an empty placeholder.
core_javadoc="$group_dir/connectivity-core-jvm/$version/connectivity-core-jvm-$version-javadoc.jar"
if [[ -f "$core_javadoc" ]] && unzip -l "$core_javadoc" | grep "index.html" > /dev/null; then
  ok "javadoc jar contains generated API docs"
else
  fail "javadoc jar is empty or missing index.html"
fi

if ((failures > 0)); then
  echo "$failures publication problem(s) found"
  exit 1
fi
echo "Publication for $version looks complete."
