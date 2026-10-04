# Tally — UCU budgeting app

A personal Android budgeting app that connects to United Credit Union (Missouri) through Plaid and tracks spending, income, budgets, bills and paydays.

## Install

1. On your phone, open this repo's **Releases → Latest build** and download `Tally-latest.apk`.
2. Open it and allow "Install unknown apps" for your browser when Android asks.
3. Later builds install over the top and keep your data, as long as they're signed with the same key (see *Signing* below).

## One-time Plaid setup

1. Sign up at <https://dashboard.plaid.com/signup> and choose **Personal use**. This puts you on Plaid's free **Trial plan**: real bank data, up to 10 connections.
2. **Developers → Keys**: copy your `client_id` and **Production** secret.
3. **Developers → API → Allowed Android package names**: add `com.pacemckinney.tally`.
4. Open Tally, paste the keys, then tap **Connect** and search for **United Credit Union (MO)**.

## What it does

- **Home**: available balance, *safe to spend per day until your next payday* (balance minus bills due before then), in/out/net for the month, a pace chart (this month against last month), top insights, and spending by category.
- **Activity**: searchable transactions. Tap one to re-categorize it, apply the category to every charge from that merchant, or exclude it.
- **Budgets**: monthly limits per category, with your 3-month average as a starting point. Warns at 80%, when you're over, and when you're on pace to go over.
- **Insights**: spending pace, a month-end projection, category spikes, large charges, possible double charges, subscription price increases, bank fees, low balance, detected bills and subscriptions, and your paycheck schedule.
- **Background sync** every 15 min to 3 hr, with notifications for new charges, deposits and alerts.

Transfers between your own UCU accounts (checking ↔ savings, paying a UCU loan) are matched and left out of spending and income.

## Security notes

- The Plaid keys and bank access tokens are encrypted with the phone's Android Keystore. Nothing secret is in this repo or the APK.
- The app calls Plaid directly from the phone, with no server in the middle. Plaid normally recommends a backend so the secret never sits on a device. For a single-person app on your own phone, the keystore-encrypted secret is a reasonable trade-off. If the phone is lost, rotate the secret in the Plaid dashboard.
- Your UCU login is typed into Plaid's own screen. Tally never sees it.
- Bank data is excluded from Android cloud backups. There's an optional fingerprint/PIN lock.

## Signing

CI signs the APK with the key in the `SIGNING_*` repository secrets. Without them it falls back to a throwaway debug key, and each build would then need an uninstall first. To set them up, go to **Settings → Secrets and variables → Actions** and add:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_B64` | base64 of your `.jks` keystore |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

## Build

Every push to `main` builds through GitHub Actions (`.github/workflows/build.yml`), runs the insight-engine unit tests, and publishes the APK to the `latest` release.
