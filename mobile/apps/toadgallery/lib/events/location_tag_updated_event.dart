import 'package:toadgallery/events/event.dart';
import "package:toadgallery/models/local_entity_data.dart";
import "package:toadgallery/models/location_tag/location_tag.dart";

class LocationTagUpdatedEvent extends Event {
  final List<LocalEntity<LocationTag>>? updatedLocTagEntities;
  final LocTagEventType type;

  LocationTagUpdatedEvent(this.type, {this.updatedLocTagEntities});
}

enum LocTagEventType { add, update, delete, dataSetLoaded }
