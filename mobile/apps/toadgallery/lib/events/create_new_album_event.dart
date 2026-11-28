import "package:toadgallery/events/event.dart";
import "package:toadgallery/models/collection/collection.dart";

class CreateNewAlbumEvent extends Event {
  final Collection collection;

  CreateNewAlbumEvent(this.collection);
}
