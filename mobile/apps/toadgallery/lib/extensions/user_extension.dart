import "package:toadgallery/models/api/collection/user.dart";
import "package:toadgallery/models/ml/face/person.dart";
import "package:toadgallery/services/machine_learning/face_ml/person/person_service.dart";

extension UserExtension on User {
  PersonEntity? get _person => _personFor(id, email);

  String? get displayName => _person?.data.name ?? label;

  String? get linkedPersonID => _person?.remoteID;

  String get nameOrEmail {
    if (PersonService.isInitialized) {
      return displayName ?? email.substring(0, email.indexOf("@"));
    } else {
      return email.substring(0, email.indexOf("@"));
    }
  }
}

extension UserSuggestionExtension on UserSuggestion {
  PersonEntity? get _person => _personFor(userID, email);

  String? get displayName => _person?.data.name ?? label;
}

PersonEntity? _personFor(int? userID, String email) =>
    PersonService.instance.getCachedPersonForUser(userID, email);
