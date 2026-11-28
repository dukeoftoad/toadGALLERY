# ToadGallery

**ToadGallery** is a private, local-first AI photo gallery built on Flutter and Rust, featuring on-device **Face Recognition & Clustering** and **Magic Semantic Search** with a Sage & Forest UI/UX theme.

## Features

- **Face Recognition & Clustering**: On-device face detection, embeddings, clustering, and people albums (`services/machine_learning/face_ml`, `MLDataDB.localGalleryInstance`).
- **Magic Search & Correlated ML**: On-device CLIP semantic search (`services/machine_learning/semantic_search`, `search_service.dart`, `magic_cache_service.dart`), OCR text search (`ocr_service.dart`), similar images (`similar_images_service.dart`), and smart memories (`smart_memories_*`).
- **Local Gallery**: Device albums, favorites, hidden folder, trash, deduplication, and map view.

## Build Instructions

```bash
cd mobile
flutter pub get
cd apps/toadgallery
flutter run
```

## License & Third-Party Models

- **Project License**: ToadGallery is licensed under the **GNU General Public License v3.0 or later** (`GPL-3.0-or-later`). See [LICENSE](LICENSE) for the full license text.
- **Models**: ToadGallery uses Ente ML models under their respective licenses.
