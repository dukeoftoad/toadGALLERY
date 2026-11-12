package com.toadgallery.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope

// ---------------------------------------------------------------------------
// The r57 panel seam (F-58a): ONE host for the photo editor's ten tool
// panels, the seam the r54 chassis wanted and the r56 ledger scheduled.
//
// Before this round the ten panels were ten `when` branches inside
// `EditorContent` (a ~3,900-line composable), each reading ~4-23 closure
// locals --- unrenderable in isolation, which is exactly why the edit app's
// screenshot harness (r56) could pin only the chrome and the control kit.
// This host is the seam: it receives the chassis state EXPLICITLY (value +
// `on...Change` pairs --- the state-hoisting pattern; nothing is captured)
// and dispatches to the ten extracted panel composables. The bodies moved
// verbatim; the ONLY behavioral surface (which value each assignment lands
// in) is preserved by construction, and the committed Roborazzi chassis
// baselines gate the round to zero pixel drift.
//
// r66: `previewHistogram` rides the seam to the Adjust panel (the live
// tonal chart's source, computed by the chassis's preview render).
//
// r68: the scope pair (`scopeMode`/`zebraActive`) and the curve deck's
// entry pair (`curveDeckOpen`) ride the seam the same way --- the
// chassis owns them because they steer chassis-level surfaces (the
// zebra paints the canvas; the curve deck hosts the Full state).
// ---------------------------------------------------------------------------

@Composable
internal fun PhotoPanelHost(
    tool: PhotoScreen,
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    previewBase: Bitmap?,
    previewHistogram: EditorHistogram?,
    commit: (EditRecipe) -> Unit,
    activity: EditActivity,
    uiScope: CoroutineScope,
    looks: Map<String, CubeLut>,
    onLooksVersionChange: () -> Unit,
    importError: String?,
    importLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    sweepPendingDrafts: () -> Unit,
    commitSelectedLayer: ((TextLayer) -> TextLayer) -> Unit,
    commitTextDraftIfPending: () -> Unit,
    textDraft: Pair<String, String>?,
    onTextDraftChange: (Pair<String, String>?) -> Unit,
    selectedTextLayerId: String?,
    onSelectTextLayerChange: (String?) -> Unit,
    selectedStickerLayerId: String?,
    onSelectStickerLayerChange: (String?) -> Unit,
    selectedRegionLayerId: String?,
    onSelectRegionLayerChange: (String?) -> Unit,
    commitSelectedStickerLayer: ((StickerLayer) -> StickerLayer) -> Unit,
    importedStickers: Map<String, Bitmap>,
    stickerImportError: String?,
    stickerImportLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    commitSelectedDrawLayer: ((DrawLayer) -> DrawLayer) -> Unit,
    drawMode: Boolean,
    onDrawModeChange: (Boolean) -> Unit,
    regionMode: Boolean,
    onRegionModeChange: (Boolean) -> Unit,
    eraseMode: Boolean,
    onEraseModeChange: (Boolean) -> Unit,
    brushColor: Long,
    onBrushColorChange: (Long) -> Unit,
    brushWidth: Int,
    onBrushWidthChange: (Int) -> Unit,
    brushOpacity: Int,
    onBrushOpacityChange: (Int) -> Unit,
    brushSmoothing: Int,
    onBrushSmoothingChange: (Int) -> Unit,
    selectedDrawLayerId: String?,
    onSelectDrawLayerChange: (String?) -> Unit,
    commitSelectedRegionLayer: ((RegionLayer) -> RegionLayer) -> Unit,
    regionKind: RegionKind,
    onRegionKindChange: (RegionKind) -> Unit,
    regionStrength: Int,
    onRegionStrengthChange: (Int) -> Unit,
    importedFrames: Map<String, Bitmap>,
    frameImportError: String?,
    frameImportLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    selectedEraseLayerId: String?,
    onSelectEraseLayerChange: (String?) -> Unit,
    aiBusy: Boolean,
    importedSkies: Map<String, Bitmap>,
    skyImportError: String?,
    skyImportLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    retouchConsent: RetouchConsent,
    onRetouchConsentChange: (RetouchConsent) -> Unit,
    retouchConsentDialog: Boolean,
    onRetouchConsentDialogChange: (Boolean) -> Unit,
    runBokehEstimate: () -> Unit,
    runRetouchDetect: () -> Unit,
    selectSky: (String, Boolean) -> Unit,
    selectedBand: String,
    onSelectedBandChange: (String) -> Unit,
    scopeMode: PhotoScopeMode,
    onScopeModeChange: (PhotoScopeMode) -> Unit,
    zebraActive: Boolean,
    onZebraActiveChange: (Boolean) -> Unit,
    curveDeckOpen: Boolean = false,
    onCurveDeckOpenChange: (Boolean) -> Unit = {},
) {
    when (tool) {
        PhotoScreen.Crop -> CropPanel(committed, draft, onDraftChange, exporting, previewBase, commit)
        PhotoScreen.Adjust -> AdjustPanel(
            committed, draft, onDraftChange, exporting, commit, previewHistogram,
            scopeMode, onScopeModeChange, zebraActive, onZebraActiveChange,
            curveDeckOpen, onCurveDeckOpenChange,
        )
        PhotoScreen.Looks -> LooksPanel(activity, uiScope, committed, draft, onDraftChange, exporting, commit, looks, onLooksVersionChange, importError, importLauncher, previewBase)
        PhotoScreen.Text -> TextPanel(activity, committed, draft, onDraftChange, exporting, commit, sweepPendingDrafts, commitSelectedLayer, commitTextDraftIfPending, textDraft, onTextDraftChange, selectedTextLayerId, onSelectTextLayerChange, selectedStickerLayerId, onSelectStickerLayerChange, selectedRegionLayerId, onSelectRegionLayerChange)
        PhotoScreen.Stickers -> StickersPanel(committed, draft, onDraftChange, exporting, commit, sweepPendingDrafts, commitSelectedStickerLayer, importedStickers, stickerImportError, stickerImportLauncher, selectedTextLayerId, onSelectTextLayerChange, selectedStickerLayerId, onSelectStickerLayerChange, selectedRegionLayerId, onSelectRegionLayerChange)
        PhotoScreen.Draw -> DrawPanel(committed, draft, onDraftChange, exporting, commit, sweepPendingDrafts, commitSelectedDrawLayer, drawMode, onDrawModeChange, regionMode, onRegionModeChange, eraseMode, onEraseModeChange, brushColor, onBrushColorChange, brushWidth, onBrushWidthChange, brushOpacity, onBrushOpacityChange, brushSmoothing, onBrushSmoothingChange, selectedTextLayerId, onSelectTextLayerChange, selectedStickerLayerId, onSelectStickerLayerChange, selectedDrawLayerId, onSelectDrawLayerChange, selectedRegionLayerId, onSelectRegionLayerChange)
        PhotoScreen.Regions -> RegionsPanel(committed, draft, onDraftChange, exporting, commit, sweepPendingDrafts, commitSelectedRegionLayer, drawMode, onDrawModeChange, regionMode, onRegionModeChange, eraseMode, onEraseModeChange, regionKind, onRegionKindChange, regionStrength, onRegionStrengthChange, selectedTextLayerId, onSelectTextLayerChange, selectedStickerLayerId, onSelectStickerLayerChange, selectedDrawLayerId, onSelectDrawLayerChange, selectedRegionLayerId, onSelectRegionLayerChange)
        PhotoScreen.Frames -> FramesPanel(committed, draft, onDraftChange, exporting, commit, sweepPendingDrafts, importedFrames, frameImportError, frameImportLauncher)
        PhotoScreen.Ai -> AiPanel(activity, committed, draft, onDraftChange, exporting, commit, sweepPendingDrafts, drawMode, onDrawModeChange, regionMode, onRegionModeChange, eraseMode, onEraseModeChange, selectedTextLayerId, onSelectTextLayerChange, selectedStickerLayerId, onSelectStickerLayerChange, selectedDrawLayerId, onSelectDrawLayerChange, selectedRegionLayerId, onSelectRegionLayerChange, selectedEraseLayerId, onSelectEraseLayerChange, aiBusy, importedSkies, skyImportError, skyImportLauncher, retouchConsent, onRetouchConsentChange, retouchConsentDialog, onRetouchConsentDialogChange, runBokehEstimate, runRetouchDetect, selectSky)
        PhotoScreen.Color -> ColorPanel(committed, exporting, commit, selectedBand, onSelectedBandChange)
        PhotoScreen.Main -> Unit
    }
}
