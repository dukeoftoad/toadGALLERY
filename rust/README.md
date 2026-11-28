# Rust

Cargo workspace for ToadGallery's Rust backend and bindings.

## Development

```sh
cargo fmt                                  # Format
cargo clippy                               # Lint
cargo build                                # Build
cargo check -p ente_photos_frb             # Check photos FRB crate
cargo test --workspace --exclude ente-cli  # Test libraries with SQLite
cargo test -p ente-cli                     # Test CLI with SQLCipher
```

CI runs with `RUSTFLAGS="-D warnings"` so warnings will cause checks to fail.

Other useful commands:

```sh
cargo codegen native  # Regenerate bindings used by native apps
cargo codegen frb     # Regenerate bindings used by Flutter apps
cargo codegen napi    # Regenerate bindings used by Desktop (Electron) apps
```

## License & Third-Party Models

- **Project License**: ToadGallery is licensed under the **GNU General Public License v3.0 or later** (`GPL-3.0-or-later`). See [LICENSE](../LICENSE).
- **Models**: ToadGallery uses Ente ML models under their respective licenses.
