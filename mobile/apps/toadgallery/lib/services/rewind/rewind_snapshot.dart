import "dart:io";

import "package:path/path.dart" as path;
import "package:toadgallery/core/configuration.dart";
import "package:toadgallery/db/ml/db.dart";
import "package:toadgallery/db/ml/rust_db.dart";
import "package:toadgallery/service_locator.dart";
import "package:toadgallery/services/rewind/rewind_request.dart";
import "package:toadgallery/src/rust/api/ml_db_api.dart" show MlStore;
import "package:toadgallery/src/rust/api/rewind_api.dart" as rust;

Future<String> exportRewindSnapshot() async {
  final snapshot = Directory(
    path.join(Configuration.instance.getTempDirectory(), "rewind_snapshot"),
  );
  await snapshot.create(recursive: true);
  final request = await buildRewindRequest(DateTime.now());
  await File(
    path.join(snapshot.path, "request.json"),
  ).writeAsBytes(await rust.encodeRewindRequest(request: request));
  return snapshot.path;
}

Future<File> exportRewindBadgeSignals() async {
  final signals = await rust.rewindBadgeSignals(
    request: await buildRewindRequest(DateTime.now()),
    store: await _rewindMlStore(),
  );
  final file = File(
    path.join(
      Configuration.instance.getTempDirectory(),
      "rewind_badge_signals.json",
    ),
  );
  await file.writeAsString(signals, flush: true);
  return file;
}

Future<MlStore?> _rewindMlStore() async {
  if (!rewindUsesMl || !MLDataDB.isRustBackend) return null;
  final db = isLocalGalleryMode
      ? RustMLDataDB.localGalleryInstance
      : RustMLDataDB.instance;
  return db.store;
}
