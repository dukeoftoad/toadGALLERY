import "package:toadgallery/events/event.dart";

class PauseVideoEvent extends Event {
  final String? fileTag;

  PauseVideoEvent({this.fileTag});
}
