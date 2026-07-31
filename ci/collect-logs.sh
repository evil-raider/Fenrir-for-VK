#!/usr/bin/env bash
set -uo pipefail
RUN_ID="${GITHUB_RUN_ID:-manual}"
REPO="${GITHUB_REPOSITORY}"
REPO_URL="https://x-access-token:${GH_TOKEN}@github.com/${REPO}.git"
sleep 2
git config --global user.name "github-actions[bot]"
git config --global user.email "github-actions[bot]@users.noreply.github.com"
rm -rf /tmp/ci-logs
if git clone --depth 1 --branch ci-logs "$REPO_URL" /tmp/ci-logs 2>/dev/null; then
  echo "cloned existing ci-logs"
else
  git clone --depth 1 "$REPO_URL" /tmp/ci-logs
  cd /tmp/ci-logs
  git checkout --orphan ci-logs
  git rm -rf . >/dev/null 2>&1 || true
  cd "$GITHUB_WORKSPACE"
fi
mkdir -p /tmp/ci-logs/runs
if [ -f "$GITHUB_WORKSPACE/build.log" ]; then
  cp "$GITHUB_WORKSPACE/build.log" "/tmp/ci-logs/runs/${RUN_ID}.log"
else
  echo "NO build.log (ci.sh failed before writing)" > "/tmp/ci-logs/runs/${RUN_ID}.log"
fi
cp "/tmp/ci-logs/runs/${RUN_ID}.log" "/tmp/ci-logs/latest.log"
cd /tmp/ci-logs
git add -A
git commit -m "ci-logs: run ${RUN_ID} [skip ci]" || echo "nothing to commit"
git push "$REPO_URL" HEAD:ci-logs
