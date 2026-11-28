import "package:toadgallery/events/event.dart";

class HideSharedItemsFromHomeGalleryEvent extends Event {
  final bool shouldHide;

  HideSharedItemsFromHomeGalleryEvent(this.shouldHide);
}
