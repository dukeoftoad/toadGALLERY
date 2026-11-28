import "package:toadgallery/events/event.dart";

class FileCaptionUpdatedEvent extends Event {
  final int fileGeneratedID;

  FileCaptionUpdatedEvent(this.fileGeneratedID);
}
