# Telegram import — phase 1

This patch adds Android share-sheet intake for Telegram text and images. It **does not** connect to Telegram, download channel history, OCR imported images, or run automatic channel monitoring. Shared text is matched against the watchlist and stored locally (last 100 posts). Image attachment counts are stored but image bytes are not retained. To use, in Telegram select a post and Share -> Product Watcher. Some Telegram clients may not offer Share for all posts; copying text and sharing it also works.

The Telegram `api_id` and `api_hash` are not used by this patch. Do not commit them. The eventual TDLib phase requires account authentication and a separately packaged native TDLib build.
