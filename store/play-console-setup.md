# Play Console & AdMob setup checklist (Serenvox, com.serenvox.app)

## Play Console
1. Create a developer account ($25 USD one-time; identity verification; personal accounts created after Nov 2023 must run a **closed test with ≥12 testers for 14 days** before production access).
2. Create app "Serenvox", default language English (Canada or US), App, Free.
3. Enrol in **Play App Signing**; upload `dist/serenvox-1.0.0-release.aab` (signed with the upload key in `keystore/`).
4. **Monetize → In-app products → Create product**: Product ID `remove_ads` (one-time / non-consumable), name "Remove ads", price **US$2.99** (let Play auto-convert, e.g. ~CA$3.99). Activate it. Add a payments profile first. Add licence testers under Settings → License testing to test purchases.
5. App content:
   - Privacy policy URL: host `store/privacy-policy.html` (e.g. GitHub Pages) and set `PRIVACY_URL` in `ui/AppRoot.kt`, rebuild.
   - Ads: **Yes, contains ads**.
   - Advertising ID declaration: **Yes**, used for Advertising/marketing and Analytics (AdMob).
   - Content rating (IARC questionnaire): Utility/productivity, no user-generated content shared, has ads, has digital purchases → expected Everyone / PEGI 3.
   - Target audience: 13+ (avoid "children" to stay out of Families policy).
   - Foreground service declaration: type **mediaPlayback** — "plays user-requested speech audio in the background with media controls". Short video may be requested.
   - Data safety (see below).
6. Store listing from `store/play-listing.md` plus graphics.

## Data safety form (suggested answers — verify against Google's current AdMob guidance)
- Data collected/shared: **Yes** (due to AdMob SDK).
  - Device or other IDs → Advertising ID: collected & shared; purpose Advertising/marketing, Analytics, Fraud prevention.
  - App activity → App interactions: collected & shared (ad interactions); Advertising, Analytics.
  - App info & performance → Crash logs, Diagnostics: collected & shared; Analytics, Fraud prevention.
  - Location → Approximate location (from IP): collected & shared; Advertising, Fraud prevention.
- Purchase history: handled by Google Play (not collected by the developer).
- Data encrypted in transit: Yes. Users can request deletion: no developer-held data (state N/A / explain).
- User text and audio: **not collected** (processed on-device only).

## AdMob
1. Create AdMob account, add app (Android, link to the Play listing once published).
2. Create ad units: **Banner** (adaptive) and **Interstitial**.
3. Put the real App ID and unit IDs in `ads.properties` and rebuild. Keep test IDs during development; never click your own live ads.
4. **Privacy & messaging**: create a **GDPR consent message** and a **US state regulations message** for the app (the app's UMP code shows them automatically).
5. **app-ads.txt**: Play listing needs a developer website; host `https://<your-domain>/app-ads.txt` containing the line AdMob gives you, e.g. `google.com, pub-XXXXXXXXXXXXXXXX, DIRECT, f08c47fec0942fa0`.
6. Payments/tax info in AdMob (Canadian tax forms).

## Open source / GPL-3.0 compliance (do BEFORE publishing)
1. Create a public repository (e.g. GitHub `serenvox`) and push this repo. **Never** push `keystore/`, `keystore.properties`, `ads.properties` (all git-ignored).
2. Set `SOURCE_URL` in `app/src/main/java/com/serenvox/app/ui/AppRoot.kt` to that repo URL, rebuild, and tag each release (e.g. `v1.0.0`) so the source matches every published AAB.
3. Put the repo link in the Play listing full description (`[SOURCE_URL]` placeholder) and on your website.
4. Keep `app/libs/sherpa-onnx-1.13.8.aar` version noted; link upstream sources for sherpa-onnx and eSpeak NG (README does).
5. Remember: anyone may legally rebuild and redistribute Serenvox (that's the GPL). Your brand name, Play listing and signing key remain yours; consider a trademark notice if you care about the name.
