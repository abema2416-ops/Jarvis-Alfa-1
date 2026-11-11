#!/usr/bin/env bash
# Запускается на GitHub: собирает APK, публикует его и сохраняет журнал сборки в ветку build-log.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LOG="$PWD/build-full.log"
bash "$HERE/build.sh" 2>&1 | tee "$LOG"
RC="${PIPESTATUS[0]}"
REL="не публиковался"
if [ "$RC" = "0" ] && [ -n "${GH_TOKEN:-}" ]; then
  export GH_REPO="$GITHUB_REPOSITORY"
  gh release delete latest --yes --cleanup-tag >/dev/null 2>&1 || true
  sleep 3
  if gh release create latest "$PWD/build/Jarvis.apk" --target "$GITHUB_SHA" --title "Джарвис-1 Альфа, сборка ${GITHUB_RUN_NUMBER:-0}" \
       --notes "Скачайте файл Jarvis.apk ниже и установите его на телефон." 2>&1 | tee -a "$LOG"; then REL="опубликован"; else REL="ошибка публикации"; fi
fi
mkdir -p /tmp/blog && cd /tmp/blog && rm -rf .git
{
  echo "status: $RC"; echo "release: $REL"; echo "run: ${GITHUB_RUN_NUMBER:-?}"; echo "commit: ${GITHUB_SHA:-?}"; date -u
  echo "---- строки с ошибками ----"; grep -n -i -E 'error|fatal|ошибка|undefined reference|cannot find|not found|failed' "$LOG" | tail -120
  echo "---- конец журнала ----"; tail -200 "$LOG"
} > build-log.txt
git init -q && git config user.name "jarvis-ci" && git config user.email "ci@users.noreply.github.com"
git add build-log.txt && git commit -q -m "build log ${GITHUB_RUN_NUMBER:-}" \
  && git push -q -f "https://x-access-token:${GH_TOKEN:-}@github.com/${GITHUB_REPOSITORY}" HEAD:refs/heads/build-log || echo "журнал не удалось сохранить"
exit "$RC"
