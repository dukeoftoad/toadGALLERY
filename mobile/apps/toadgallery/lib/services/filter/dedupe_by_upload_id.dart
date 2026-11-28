import 'package:toadgallery/models/file/file.dart';
import "package:toadgallery/services/filter/filter.dart";

class DedupeUploadIDFilter extends Filter {
  final Set<int> trackedUploadIDs = {};

  @override
  bool filter(EnteFile file) {
    if (!file.isUploaded) {
      return true;
    }
    if (trackedUploadIDs.contains(file.uploadedFileID!)) {
      return false;
    }
    trackedUploadIDs.add(file.uploadedFileID!);
    return true;
  }
}
