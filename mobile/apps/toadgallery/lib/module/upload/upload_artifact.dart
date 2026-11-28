import 'package:path/path.dart' as p;
import 'package:toadgallery/core/constants.dart';

bool isUploadTempArtifactPath(String path) {
  final fileName = p.basename(path);
  return fileName.startsWith(uploadTempFilePrefix) &&
      fileName.endsWith('.encrypted');
}
