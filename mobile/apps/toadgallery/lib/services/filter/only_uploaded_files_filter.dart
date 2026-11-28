import "package:toadgallery/models/file/file.dart";
import "package:toadgallery/services/filter/filter.dart";

class OnlyUploadedFilesFilter extends Filter {
  @override
  bool filter(EnteFile file) {
    return file.isUploaded;
  }
}
