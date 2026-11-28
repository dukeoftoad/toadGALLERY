import "package:toadgallery/models/file/extensions/file_props.dart";
import "package:toadgallery/models/file/file.dart";
import "package:toadgallery/services/filter/filter.dart";

class SkipSharedFileFilter extends Filter {
  @override
  bool filter(EnteFile file) {
    return file.uploaderName == null && file.isOwner;
  }
}
