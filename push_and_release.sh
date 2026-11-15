#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# SparkTube v1.7.8 — Automated GitHub Push & Release Script
# Complete Age-Restricted (18+) Playback Unlock + 0:00 Infinite Retry Loop Fix
# ==============================================================================

REPO_URL="https://github.com/khad3m/SparkTubes.git"
TAG="v1.7.8"

if [ -z "${GITHUB_PAT:-}" ]; then
  echo "Usage: GITHUB_PAT=<your_github_pat> ./push_and_release.sh"
  exit 1
fi

AUTH_URL="https://x-access-token:${GITHUB_PAT}@github.com/khad3m/SparkTubes.git"
WORK_DIR="$(mktemp -d)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Cloning khad3m/SparkTubes (main)..."
git clone --depth 1 --branch main "$AUTH_URL" "$WORK_DIR/SparkTube"

echo "==> Applying SparkTube v1.7.8 source files..."
cp -r "$SCRIPT_DIR/app/"* "$WORK_DIR/SparkTube/app/"
cp -f "$SCRIPT_DIR/README_UPGRADE.md" "$WORK_DIR/SparkTube/README_UPGRADE.md"
cp -f "$SCRIPT_DIR/CHANGES.md" "$WORK_DIR/SparkTube/CHANGES.md"

cd "$WORK_DIR/SparkTube"
git config user.name "devfahim00"
git config user.email "devfahim00@users.noreply.github.com"

git add -A
git commit -m "fix(playback): fix 18+ age-restricted unlock & 0:00 infinite retry loop (v1.7.8)"
git push origin main

echo "==> Creating and pushing release tag ${TAG}..."
git tag -f "$TAG"
git push -f origin "$TAG"

echo "==> Done! GitHub Actions is now building and publishing Release ${TAG} at:"
echo "    https://github.com/khad3m/SparkTubes/releases/tag/${TAG}"
