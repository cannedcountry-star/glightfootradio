# Gordon Lightfoot Radio (Android)

ネットラジオ **Exclusively Gordon Lightfoot**（Exclusive Radio）を聴くための Android アプリです。

## できること

- 画面を消しても・他のアプリを使っていても再生を続ける（通知・ロック画面・イヤホンのボタンで再生／停止）
- 今かかっている曲名・アーティスト・アルバム名・ジャケットを表示（曲名は配信の ICY メタデータ、ジャケットは iTunes 検索）
- 最近かかった曲の履歴（タップで YouTube 検索）
- ホーム画面ウィジェット（曲名・ジャケット・▶/■）
- 電波が切れても自動でつなぎ直す
- 配信 URL をアプリ内で変更可能（右上の「︙」→「配信URLを変更」）。.pls / .m3u のプレイリスト URL や HLS（.m3u8）にも対応

## インストール

1. スマホのブラウザで [Releases](https://github.com/cannedcountry-star/glightfootradio/releases/latest) を開く
2. `GLightfootRadio-1.0.N.apk` をダウンロードして開く
3. 「提供元不明のアプリ」の許可を求められたら、ブラウザ（またはファイルアプリ）に許可する

更新するときは、新しい APK をそのまま上書きインストールできます（毎回同じ署名鍵でビルドしているため）。

## ビルド

`main` に push すると GitHub Actions（`.github/workflows/android.yml`）が APK をビルドし、Releases に公開します。
手動で実行するときは Actions タブの「Android APK」→「Run workflow」。

署名鍵はリポジトリの Secrets（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）から読み込みます。

## 構成

| ファイル | 役割 |
|---|---|
| `PlaybackService.kt` | 再生本体（Media3 ExoPlayer + MediaSessionService）。曲名取得・ジャケット検索・自動再接続 |
| `MainActivity.kt` | 再生画面。MediaController で操作・表示 |
| `RadioWidgetProvider.kt` / `ToggleActivity.kt` | ホーム画面ウィジェット |
| `Radio.kt` / `Net.kt` | 局の情報・保存データ / 通信処理 |

PC 版（Electron のデスクトップウィジェット）は別に作っています。
