# Security

## Reporting a vulnerability

Please report security issues privately to **support@opensolr.com** with "Opensolr Photos security" in the
subject. Include the version of the app, the steps to reproduce, and what an attacker could achieve.
Please do not open a public GitHub issue for a vulnerability.

## Scope

- This app: the code in this repository and the signed APKs published under
  [Releases](https://github.com/phpcip/opensolr-photos/releases).
- The Opensolr endpoints the app uses: `/app/authorize`, `/app/token` and the REST API calls listed in
  [How it works](docs/how-it-works.md#what-the-app-calls).

## Verifying a download

Every release APK is signed with the same key. Check the certificate before installing:

```bash
apksigner verify --print-certs opensolr-photos.apk
```

The SHA-256 digest must be:

```
1a54dace0d5d7e80df76d7a287ab3fdbc4b9ce7cef9c172e329159373a194151
```

The same fingerprint is published by opensolr.com in
[/.well-known/assetlinks.json](https://opensolr.com/.well-known/assetlinks.json), which is what lets Android
hand the sign-in callback to this app and no other.

The design is described in [privacy and security](docs/privacy-and-security.md).
