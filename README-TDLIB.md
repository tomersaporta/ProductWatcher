# ProductWatcher TDLib integration — experimental

This patch adds an in-app TDLib login and a selectable list of Telegram supergroups/channels. It reads the latest 100 posts and matches message text/captions. It does NOT yet OCR photo pixels, save offers, run persistent background monitoring, or guarantee that every subscribed channel appears in the initial chat list.

The Gradle dependency is a community-maintained prebuilt TDLib JNI AAR via JitPack, not an official Telegram-published Maven package. Review its provenance before installing or entering credentials. No API keys are checked into Git.

If the GitHub Actions build fails, send the build log. No local Android SDK build was available to verify this patch.
