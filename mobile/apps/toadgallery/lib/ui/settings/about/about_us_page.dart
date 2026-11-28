import "package:ente_components/ente_components.dart";
import "package:ente_components/hugeicons.dart";
import "package:ente_strings/ente_strings.dart";
import "package:ente_ui/components/settings/about_settings_section.dart";
import "package:flutter/material.dart";
import "package:toadgallery/service_locator.dart";
import "package:toadgallery/theme/ente_theme.dart";
import "package:toadgallery/ui/notification/toast.dart";
import "package:toadgallery/ui/notification/update/change_log_page.dart";
import "package:toadgallery/ui/settings/app_update_sheet.dart";

class AboutUsPage extends StatelessWidget {
  const AboutUsPage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = context.strings;

    return SettingsPageScaffold(
      title: l10n.about,
      children: [
        const _ToadGalleryMasthead(),
        SettingsItem(
          title: l10n.whatsNew,
          icon: HugeIcons.strokeRoundedParty,
          showOnlyLoadingState: true,
          onTap: () => showChangeLogSheet(context),
        ),
        const SizedBox(height: Spacing.sm),
        AboutSettingsSection(
          onCheckForUpdates: updateService.isIndependent()
              ? () => _checkForUpdates(context)
              : null,
        ),
      ],
    );
  }

  Future<void> _checkForUpdates(BuildContext context) async {
    final shouldUpdate = await updateService.shouldUpdate();
    final latestVersion = updateService.getLatestVersionInfo();
    if (!context.mounted) return;
    if (latestVersion == null) {
      showShortToast(context, context.strings.unableToCheckForUpdatesRightNow);
      return;
    }
    if (!shouldUpdate) {
      showShortToast(context, context.strings.youAreOnTheLatestVersion);
      return;
    }
    await showAppUpdateSheet(context, latestVersionInfo: latestVersion);
  }
}

/// toadGALLERY masthead shown at the top of the About page:
/// the toad mark, the wordmark and the tagline, per the v2 design.
class _ToadGalleryMasthead extends StatelessWidget {
  const _ToadGalleryMasthead();

  @override
  Widget build(BuildContext context) {
    final colorScheme = getEnteColorScheme(context);
    return Column(
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(28),
          child: Image.asset(
            "assets/toad_icon.png",
            width: 96,
            height: 96,
            fit: BoxFit.cover,
          ),
        ),
        const SizedBox(height: Spacing.md),
        Text(
          "toadGALLERY",
          style: TextStyle(
            fontFamily: "Inter",
            fontSize: 20,
            height: 28 / 20,
            fontWeight: FontWeight.w700,
            letterSpacing: 0.2,
            color: colorScheme.textBase,
          ),
        ),
        const SizedBox(height: Spacing.xs),
        Text(
          "A little home for your moments.",
          style: TextStyle(
            fontFamily: "Inter",
            fontSize: 13,
            height: 18 / 13,
            fontWeight: FontWeight.w500,
            color: colorScheme.textMuted,
          ),
        ),
        const SizedBox(height: Spacing.lg),
      ],
    );
  }
}
