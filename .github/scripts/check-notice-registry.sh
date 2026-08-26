#!/usr/bin/env bash
# Гейт реестра локальных патчей вендоренного UI.
#
# Два правила, оба про один класс поломок:
#   1. NOTICE.txt заморожен. Он держит только шапку вендоринга и объяснение
#      формата. Записи о патчах живут по файлу в NOTICE.d/, иначе параллельные
#      патчи снова начнут сталкиваться на одних строках.
#   2. Патч вендоренного кода обязан нести запись в NOTICE.d/. Правило и раньше
#      было записано в конвенциях проекта, но исполнялось только памятью автора.
#
# Запускается в CI на PR: сравнивает ветку с базой.
set -euo pipefail

base="${1:-origin/${GITHUB_BASE_REF:-develop/2.x}}"
ui="spring-ai-mcp-inspector-ui/upstream-client"
changed="$(git diff --name-only "$base"...HEAD)"

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

if grep -qE "^$ui/src/" <<< "$changed"; then
  if ! grep -qE "^$ui/NOTICE\.d/" <<< "$changed"; then
    echo "FAIL: тронут вендоренный код ($ui/src), но ни один файл в $ui/NOTICE.d/ не добавлен и не изменён."
    echo "      Каждая правка поверх upstream регистрируется записью в NOTICE.d/,"
    echo "      иначе при следующем подъёме версии её никто не найдёт."
    echo "      Изменённые файлы под src:"
    grep -E "^$ui/src/" <<< "$changed" | sed 's/^/        /'
    fail=1
  fi
fi

if [[ $fail -eq 0 ]]; then
  echo "OK: реестр локальных патчей в порядке."
fi
exit $fail
