import "package:toadgallery/events/event.dart";

class ComputeControlEvent extends Event {
  final bool shouldRun;

  ComputeControlEvent(this.shouldRun);
}
