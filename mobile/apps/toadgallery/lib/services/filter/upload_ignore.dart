import 'package:toadgallery/models/file/file.dart';
import "package:toadgallery/services/filter/filter.dart";
import "package:toadgallery/services/ignored_files_service.dart";

class UploadIgnoreFilter extends Filter {
  Map<String, String> idToReasonMap;

  UploadIgnoreFilter(this.idToReasonMap) : super();

  @override
  bool filter(EnteFile file) {
    if (file.isUploaded) return true;
    return !IgnoredFilesService.instance.shouldSkipUpload(idToReasonMap, file);
  }
}
