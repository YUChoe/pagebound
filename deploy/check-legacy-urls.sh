#!/usr/bin/env bash
# 레거시(Wiki.js) URL 301 확인 (spec/phases/M8-cutover.md 전환 절차 4번)
# 사용: ./check-legacy-urls.sh http://<legacy-wiki> /path/to/wikijs/database.sqlite [개수=10]
set -euo pipefail
BASE="${1:?base url}"
SRC="${2:?wikijs database.sqlite}"
COUNT="${3:-10}"
fail=0
while IFS= read -r path; do
  encoded=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$path")
  read -r code location < <(curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' "$BASE/$encoded")
  if [[ "$code" == "301" && "$location" == *"/w/"* ]]; then
    echo "OK   $code /$path -> $location"
  else
    echo "FAIL $code /$path -> $location"
    fail=1
  fi
done < <(sqlite3 "$SRC" "SELECT path FROM pages ORDER BY random() LIMIT $COUNT")
exit $fail
