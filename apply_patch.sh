#!/usr/bin/env bash
set -euo pipefail
if [[ ! -d app/src/main/java/com/productwatcher/app ]]; then
  echo 'Run from the root of the ProductWatcher repository.' >&2; exit 1
fi
# The patch ZIP already overlays corrected Kotlin sources and manifest.
# Remove legacy local-folder scanner (intentionally retired).
rm -f app/src/main/java/com/productwatcher/app/ScanWorker.kt
printf 'Monitoring startup implementation: '; grep -q 'fun ensureAutomatic' app/src/main/java/com/productwatcher/app/MonitorControl.kt && echo OK
printf 'Downloaded-photo cleanup method: '; grep -q 'fun deleteDownloadedPhoto' app/src/main/java/com/productwatcher/app/TelegramClient.kt && echo OK
printf 'New spatial extraction method: '; grep -q 'object OfferExtractor' app/src/main/java/com/productwatcher/app/OfferExtractor.kt && echo OK
printf '\nSource installed. Run: git add -A && git commit ... && git push origin main\n'
