import "package:toadgallery/events/event.dart";
import "package:toadgallery/models/file/file.dart";

class FileUploadedEvent extends Event {
  final EnteFile file;

  FileUploadedEvent(this.file);
}
