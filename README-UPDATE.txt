ProductWatcher UI refresh

This update replaces MainActivity.kt with a dark modern dashboard, watchlist cards, scan status and Android Share report action.
It DOES NOT connect ChatGPT automatically, implement Telegram TDLib, or add offer history. Sharing sends only summary text via Android share sheet.

Upload ProductWatcher-UI-update.zip into GitHub Codespaces repository root, then run:
  unzip -o ProductWatcher-UI-update.zip
  rm ProductWatcher-UI-update.zip
  git add -A
  git commit -m "Refresh ProductWatcher UI and add report sharing"
  git push origin main

GitHub Actions will build the new APK. Inspect the logs if compilation fails.
