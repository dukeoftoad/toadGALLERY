import "package:ente_strings/ente_strings.dart";
import 'package:flutter/material.dart';
import 'package:toadgallery/core/event_bus.dart';
import 'package:toadgallery/db/files_db.dart';
import 'package:toadgallery/events/collection_meta_event.dart';
import 'package:toadgallery/events/collection_updated_event.dart';
import 'package:toadgallery/events/files_updated_event.dart';
import 'package:toadgallery/models/collection/collection.dart';
import 'package:toadgallery/models/file_load_result.dart';
import 'package:toadgallery/models/gallery_type.dart';
import "package:toadgallery/models/search/hierarchical/album_filter.dart";
import "package:toadgallery/models/search/hierarchical/hierarchical_search_filter.dart";
import 'package:toadgallery/models/selected_files.dart';
import 'package:toadgallery/services/ignored_files_service.dart';
import "package:toadgallery/ui/components/empty_state_component.dart";
import 'package:toadgallery/ui/viewer/actions/file_selection_overlay_bar.dart';
import 'package:toadgallery/ui/viewer/gallery/gallery.dart';
import 'package:toadgallery/ui/viewer/gallery/gallery_app_bar_widget.dart';
import "package:toadgallery/ui/viewer/gallery/hierarchical_search_gallery.dart";
import "package:toadgallery/ui/viewer/gallery/state/gallery_boundaries_provider.dart";
import "package:toadgallery/ui/viewer/gallery/state/gallery_files_inherited_widget.dart";
import "package:toadgallery/ui/viewer/gallery/state/inherited_search_filter_data.dart";
import "package:toadgallery/ui/viewer/gallery/state/search_filter_data_provider.dart";
import "package:toadgallery/ui/viewer/gallery/state/selection_state.dart";

class UnCategorizedPage extends StatelessWidget {
  final String tagPrefix;
  final Collection collection;
  final GalleryType appBarType;
  final GalleryType overlayType;
  final _selectedFiles = SelectedFiles();

  UnCategorizedPage(
    this.collection, {
    this.tagPrefix = "Uncategorized_page",
    this.appBarType = GalleryType.uncategorized,
    this.overlayType = GalleryType.uncategorized,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    final appBar = GalleryAppBarWidget.sliverConfig(
      appBarType,
      context.strings.uncategorized,
      _selectedFiles,
      collection: collection,
    );
    final gallery = Gallery(
      appBar: appBar,
      asyncLoader: (creationStartTime, creationEndTime, {limit, asc}) async {
        final FileLoadResult result = await FilesDB.instance
            .getFilesInCollection(
              collection.id,
              creationStartTime,
              creationEndTime,
              limit: limit,
              asc: asc,
            );
        final ignoredIDs =
            await IgnoredFilesService.instance.idToIgnoreReasonMap;
        result.files.removeWhere(
          (f) =>
              f.uploadedFileID == null &&
              IgnoredFilesService.instance.shouldSkipUpload(ignoredIDs, f),
        );
        return result;
      },
      reloadEvent: Bus.instance.on<CollectionUpdatedEvent>().where(
        (event) => event.collectionID == collection.id,
      ),
      removalEventTypes: const {
        EventType.deletedFromRemote,
        EventType.deletedFromEverywhere,
        EventType.hide,
      },
      forceReloadEvents: [
        Bus.instance.on<CollectionMetaEvent>().where(
          (event) =>
              event.id == collection.id &&
              event.type == CollectionMetaEventType.sortChanged,
        ),
      ],
      tagPrefix: tagPrefix,
      selectedFiles: _selectedFiles,
      sortOrderFn: () => collection.pubMagicMetadata.fileSortOrder,
      initialFiles: null,
      albumName: context.strings.uncategorized,
      emptyState: EmptyStateComponent(
        assetPath: "assets/empty-uncategorized.png",
        title: context.strings.uncategorizedItemsWillShowUpHere,
      ),
    );
    return GalleryBoundariesProvider(
      child: GalleryFilesState(
        child: InheritedSearchFilterDataWrapper(
          searchFilterDataProvider: SearchFilterDataProvider(
            initialGalleryFilter: AlbumFilter(
              collectionID: collection.id,
              albumName: collection.displayName,
              occurrence: kMostRelevantFilter,
            ),
          ),
          child: Scaffold(
            body: SelectionState(
              selectedFiles: _selectedFiles,
              child: Stack(
                alignment: Alignment.bottomCenter,
                children: [
                  Builder(
                    builder: (context) {
                      return ValueListenableBuilder(
                        valueListenable: InheritedSearchFilterData.of(
                          context,
                        ).searchFilterDataProvider!.isSearchingNotifier,
                        builder: (context, isSearching, _) {
                          return isSearching
                              ? HierarchicalSearchGallery(
                                  tagPrefix: tagPrefix,
                                  selectedFiles: _selectedFiles,
                                  appBar: appBar,
                                )
                              : gallery;
                        },
                      );
                    },
                  ),
                  FileSelectionOverlayBar(
                    overlayType,
                    _selectedFiles,
                    collection: collection,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
