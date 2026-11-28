# ToadGallery Mobile App

Flutter mobile application for **ToadGallery**, a private, local-first AI photo gallery with on-device face recognition, clustering, and semantic search.

## Building from Source

1. Install [Flutter](https://flutter.dev/docs/get-started/install) and [Rust](https://www.rust-lang.org/tools/install).

2. Install Flutter workspace dependencies from `mobile/`:

   ```sh
   cd mobile
   flutter pub get
   ```

3. (Optional) If you modify the Rust FRB-exported surface under `rust/bindings/frb/`, regenerate the Flutter Rust Bridge bindings from `rust/`:

   ```sh
   cd rust
   cargo codegen frb
   ```

4. Run or build the app from `mobile/apps/toadgallery/`:

   - **Run locally**: `flutter run`
   - **Android release APK**: `flutter build apk --release --split-per-abi`
   - **iOS release (no codesign)**: `flutter build ios --release --no-codesign`

## License & Third-Party Models

- **Project License**: ToadGallery is licensed under the **GNU General Public License v3.0 or later** (`GPL-3.0-or-later`). See [LICENSE](../../../LICENSE).
- **Models**: ToadGallery uses Ente ML models under their respective licenses.
