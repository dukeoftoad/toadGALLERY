import "package:toadgallery/models/file/file.dart";
import "package:toadgallery/models/search/hierarchical/hierarchical_search_filter.dart";
import "package:toadgallery/models/search/search_types.dart";

abstract class SearchResult {
  ResultType type();

  String name();

  EnteFile? previewThumbnail();

  String heroTag() {
    return '${type().toString()}_${name()}';
  }

  List<EnteFile> resultFiles();

  HierarchicalSearchFilter getHierarchicalSearchFilter();
}
