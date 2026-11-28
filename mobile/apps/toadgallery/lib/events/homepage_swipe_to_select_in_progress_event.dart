import "package:toadgallery/events/event.dart";

class HomepageSwipeToSelectInProgressEvent extends Event {
  final bool isInProgress;
  HomepageSwipeToSelectInProgressEvent({required this.isInProgress});
}
