import "package:ente_strings/ente_strings.dart";
import "package:flutter/material.dart";
import "package:toadgallery/core/event_bus.dart";
import "package:toadgallery/db/files_db.dart";
import "package:toadgallery/events/files_updated_event.dart";
import "package:toadgallery/models/file_load_result.dart";
import "package:toadgallery/models/gallery_type.dart";
import "package:toadgallery/models/selected_files.dart";
import "package:toadgallery/services/collections_service.dart";
import "package:toadgallery/ui/viewer/actions/file_selection_overlay_bar.dart";
import "package:toadgallery/ui/viewer/gallery/gallery.dart";
import "package:toadgallery/ui/viewer/gallery/gallery_app_bar_widget.dart";
import "package:toadgallery/ui/viewer/gallery/state/gallery_boundaries_provider.dart";
import "package:toadgallery/ui/viewer/gallery/state/gallery_files_inherited_widget.dart";
import "package:toadgallery/ui/viewer/gallery/state/selection_state.dart";

class DeleteSuggestionsPage extends StatelessWidget {
  final String tagPrefix;
  final GalleryType appBarType;
  final GalleryType overlayType;
  final _selectedFiles = SelectedFiles();

  DeleteSuggestionsPage({
    this.tagPrefix = "delete_suggestions_page",
    this.appBarType = GalleryType.deleteSuggestions,
    this.overlayType = GalleryType.deleteSuggestions,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    final appBar = GalleryAppBarWidget.sliverConfig(
      appBarType,
      context.strings.deleteSuggestions,
      _selectedFiles,
    );
    final gallery = Gallery(
      appBar: appBar,
      asyncLoader: (creationStartTime, creationEndTime, {limit, asc}) async {
        final fileIDs = await CollectionsService.instance
            .fetchDeleteSuggestionFileIDs();
        if (fileIDs.isEmpty) {
          return FileLoadResult([], false);
        }
        final files = await FilesDB.instance.getFilesFromIDs(
          fileIDs,
          dedupeByUploadId: true,
        );
        return FileLoadResult(files, false);
      },
      reloadEvent: Bus.instance.on<FilesUpdatedEvent>(),
      tagPrefix: tagPrefix,
      selectedFiles: _selectedFiles,
      galleryType: GalleryType.deleteSuggestions,
      enableFileGrouping: false,
    );

    return GalleryBoundariesProvider(
      child: GalleryFilesState(
        child: Scaffold(
          body: SelectionState(
            selectedFiles: _selectedFiles,
            child: Stack(
              alignment: Alignment.bottomCenter,
              children: [
                gallery,
                FileSelectionOverlayBar(overlayType, _selectedFiles),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
