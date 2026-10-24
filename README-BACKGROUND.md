# ProductWatcher background monitoring patch

Apply over the existing repository, preferably after ProductWatcher-Auto-Telegram-OCR.zip.

- Starts a foreground service from the Telegram screen after Connect, channel selection, or START BACKGROUND MONITORING.
- Persistent notification with Stop action; TDLib stays in the app process.
- Every 5 minutes checks the selected channel for missed recent posts; TDLib UpdateNewMessage handles live messages.
- Does not request Telegram forwarding or third-party servers.
- Requires notification permission on Android 13+ for visible notification.

## Limits
Android 15+ restricts dataSync foreground services to 6 hours per 24-hour period for apps targeting API 35. The OS may stop the service, so this patch is not guaranteed 24/7 monitoring. It also does not auto-start on reboot or after force-stop. A robust always-on solution needs a different Android-compliant architecture, potentially server-side.

Not compiled or device-tested here.
