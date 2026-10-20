# MoneyBoss Android

Native Android wrapper for [MoneyBoss](https://moneyboss.netlify.app):

- WebView loads the live Netlify site with a saved user token
- Home-screen widget: current balance + add-expense button
- Google Wallet notification listener: auto-records expenses

## Build (GitHub Actions)

1. Push this repo to GitHub
2. Open **Actions** → **Build MoneyBoss APK** → run workflow (or push to `main`)
3. Download the `moneyboss-debug-apk` artifact
4. Install the APK on your phone (allow unknown sources for the installer app)

## First launch

1. Enter your 6-character MoneyBoss token
2. Allow notification access when prompted (for Wallet auto-import)
3. Add the **MoneyBoss מאזן** widget from the home-screen widget picker
