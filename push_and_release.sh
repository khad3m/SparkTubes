#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# SparkTube v1.5.4 — Automated GitHub Push & Release Script
# Upgrades UI/UX, removes Backup/Update/Telegram/About from Settings,
# pushes to main, and triggers Release v1.5.4.
# ==============================================================================

REPO_URL="https://github.com/khad3m/SparkTubes.git"
TAG="v1.5.4"

if [ -z "${GITHUB_PAT:-}" ]; then
  echo "Usage: GITHUB_PAT=<your_github_pat> ./push_and_release.sh"
  exit 1
fi

AUTH_URL="https://x-access-token:${GITHUB_PAT}@github.com/khad3m/SparkTubes.git"
WORK_DIR="$(mktemp -d)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Cloning khad3m/SparkTubes (main)..."
git clone --depth 1 --branch main "$AUTH_URL" "$WORK_DIR/SparkTube"

echo "==> Applying Apple Music UI & Settings cleanup files..."
cp -r "$SCRIPT_DIR/app/"* "$WORK_DIR/SparkTube/app/"

cd "$WORK_DIR/SparkTube"
git config user.name "devfahim00"
git config user.email "devfahim00@users.noreply.github.com"

git add -A
git commit -m "feat(ui): glass home and player, brightness/volume gestures, new app icon (v1.5.4)"
git push origin main

echo "==> Creating and pushing release tag ${TAG}..."
git tag -f "$TAG"
git push -f origin "$TAG"

echo "==> Done! GitHub Actions is now building and publishing Release ${TAG} at:"
echo "    https://github.com/khad3m/SparkTubes/releases/tag/${TAG}"
