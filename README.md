# TF Holdings

A small Android app that shows the holdings exported from the TechnoFunda laptop app, valued at live prices.

- **No personal data lives in this repository.** Holdings come from `tf_holdings.json`, which you export from
  TechnoFunda (Holdings & overview → Export for phone) and open on the phone. The app keeps it in its private storage.
- Live prices: Yahoo Finance (NSE, then BSE; SGBs BSE first) and mfapi.in for mutual-fund NAVs (numeric scheme codes).
- Each push to the `holdings` branch builds a signed APK and publishes it as a "TF Holdings build N" release.

Information only, not investment advice.
