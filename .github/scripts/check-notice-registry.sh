#!/usr/bin/env bash
# Гейт реестра локальных патчей вендоренного UI.
#
# Три правила:
#   1. NOTICE.txt заморожен. Он держит только шапку вендоринга и объяснение
#      формата. Записи о патчах живут по файлу в NOTICE.d/, иначе параллельные
#      патчи снова начнут сталкиваться на одних строках.
#   2. Патч вендоренного кода обязан нести запись в NOTICE.d/. Это относится
#      ко всем файлам upstream-client/ (включая конфигурационные, например
#      package.json), а не только к src/.
#   3. Патч вендоренного кода под src/ обязан содержать маркер
#      [spring-ai-mcp-inspector PATCH] в теле файла, иначе правка будет молча
#      перезаписана при следующей пере-вендоризации.
#
# Запускается в CI на PR: сравнивает ветку с базой.
set -euo pipefail

base="${1:-origin/${GITHUB_BASE_REF:-develop/2.x}}"
ui="spring-ai-mcp-inspector-ui/upstream-client"
changed="$(git diff --name-only "$base"...HEAD)"
# Исключаем удалённые файлы: для них не нужна ни регистрация, ни маркер
existing="$(git diff --diff-filter=d --name-only "$base"...HEAD)"

fail=0

if grep -qx "$ui/NOTICE.txt" <<< "$changed"; then
  # Шапку менять можно (смена версии upstream), список патчей в неё возвращать нельзя.
  if git diff "$base"...HEAD -- "$ui/NOTICE.txt" | grep -qE '^\+[[:space:]]*[0-9]+\.[[:space:]]'; then
    echo "FAIL: в $ui/NOTICE.txt добавлена нумерованная запись."
    echo "      Реестр разъехался на NOTICE.d/<что-делает-патч>.txt именно потому,"
    echo "      что нумерованный список конфликтовал у каждой пары параллельных PR."
    fail=1
  fi
fi

# Правило 2: любая правка в upstream-client/ (кроме NOTICE.d/ и NOTICE.txt)
# требует записи в NOTICE.d/
vendored_files="$(grep -E "^$ui/" <<< "$existing" | grep -vE "^$ui/(NOTICE\.d/|NOTICE\.txt$)" || true)"
if [[ -n "$vendored_files" ]]; then
  if ! grep -qE "^$ui/NOTICE\.d/" <<< "$changed"; then
    echo "FAIL: тронут вендоренный код ($ui/), но ни один файл в $ui/NOTICE.d/ не добавлен и не изменён."
    echo "      Каждая правка поверх upstream регистрируется записью в NOTICE.d/,"
    echo "      иначе при следующем подъёме версии её никто не найдёт."
    echo "      Изменённые файлы:"
    sed 's/^/        /' <<< "$vendored_files"
    fail=1
  fi
fi

# Правило 3: правка под src/ требует маркера PATCH в теле каждого файла
src_files="$(grep -E "^$ui/src/" <<< "$existing" || true)"
if [[ -n "$src_files" ]]; then
  missing_marker=""
  while IFS= read -r file; do
    if ! grep -qE '\[spring-ai-mcp-inspector PATCH\]' "$file" 2>/dev/null; then
      missing_marker+="$file"$'\n'
    fi
  done <<< "$src_files"
  if [[ -n "$missing_marker" ]]; then
    echo "FAIL: файлы под $ui/src/ не содержат обязательный маркер [spring-ai-mcp-inspector PATCH]:"
    sed 's/^/        /' <<< "$missing_marker"
    echo "      Без маркера правка будет молча перезаписана при следующей пере-вендоризации."
    fail=1
  fi
fi

if [[ $fail -eq 0 ]]; then
  echo "OK: реестр локальных патчей в порядке."
fi
exit $fail