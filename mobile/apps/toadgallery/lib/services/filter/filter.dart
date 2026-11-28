import 'package:toadgallery/models/file/file.dart';

abstract class Filter {
  bool filter(EnteFile file);
}
