import "package:ente_strings/ente_strings.dart";
import "package:toadgallery/locale.dart";

class LanguageService {
  static Future<StringsLocalizations> get locals async {
    final local = await getLocale();
    final s = lookupStringsLocalizations(local!);
    return s;
  }
}
