# ADB Deployment

## Objective

Deploy the phone and Wear OS release APKs without relying on stale wireless-debugging ports.

## Device Discovery

1. Run `adb mdns services`.
2. Treat both `_adb._tcp` and `_adb-tls-connect._tcp` entries as candidates.
3. Connect to every candidate using its currently advertised host and port.
4. Classify connected devices using:
   - `adb -s <serial> shell getprop ro.build.characteristics`
   - `adb -s <serial> shell getprop ro.product.model`
   - `adb -s <serial> shell wm size`
5. A Wear OS target must report `watch` in `ro.build.characteristics`.
6. A phone target must report `phone` or `default` and must not report `watch`, `tablet`, `tv`, or `automotive`.
7. Never install to an unclassified or ambiguous device.

## Release APKs

- Phone: `app/build/outputs/apk/gplay/release/app-gplay-universal-release.apk`
- Wear OS: `wearapp/build/outputs/apk/release/wearapp-release.apk`

## Deployment

1. Build both release APKs.
2. Install with `adb -s <serial> install -r <apk>`.
3. Verify the installed package version using `dumpsys package`.
4. Report any discovered device that was deliberately skipped and why.

Wireless debugging ports can change whenever the device restarts or wireless debugging is toggled. Always repeat discovery immediately before deployment.
