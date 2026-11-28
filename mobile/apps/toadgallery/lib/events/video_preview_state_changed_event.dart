import "package:toadgallery/events/event.dart";
import "package:toadgallery/models/preview/preview_item_status.dart";

class VideoPreviewStateChangedEvent extends Event {
  final int fileId;
  final PreviewItemStatus status;

  VideoPreviewStateChangedEvent(this.fileId, this.status);

  @override
  String get reason => '$runtimeType: fileId=$fileId, status=$status';
}
