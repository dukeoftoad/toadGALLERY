import "package:toadgallery/events/event.dart";
import "package:toadgallery/models/photo_review/photo_review_models.dart";

class PhotoReviewUpdatedEvent extends Event {
  PhotoReviewUpdatedEvent(Iterable<ReviewMonthKey> monthKeys)
    : monthKeys = Set<ReviewMonthKey>.unmodifiable(monthKeys);

  final Set<ReviewMonthKey> monthKeys;
}
