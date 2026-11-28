import "package:toadgallery/events/event.dart";
import "package:toadgallery/models/backup/backup_item.dart";
import "package:toadgallery/models/backup/backup_items_change.dart";

class BackupUpdatedEvent extends Event {
  BackupUpdatedEvent(this.change);

  final BackupItemsChange change;

  Map<String, BackupItem> get upserts => change.upserts;
  Set<String> get removedLocalIDs => change.removedLocalIDs;
}
