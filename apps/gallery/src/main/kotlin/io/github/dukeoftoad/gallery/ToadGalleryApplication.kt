package io.github.dukeoftoad.gallery

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

/**
 * toadGALLERY's [Application]; exists to wire Coil's video frame decoder into
 * the app-wide image loader (issue #33).
 *
 * Explicit registration is required because Coil 2.x has NO service-registry
 * or classpath auto-discovery for its optional decoder artifacts --- the
 * reflection-based auto-detection was a Coil 1.x mechanism, removed in 2.0.
 * Adding the `coil-video` dependency alone therefore changes nothing: every
 * `AsyncImage` that does not take an explicit loader resolves the app-wide
 * loader via `context.imageLoader`, which asks the application for an
 * [ImageLoaderFactory] and otherwise builds a default `ImageLoader` whose
 * component registry holds only `BitmapFactoryDecoder` plus the standard
 * fetchers. BitmapFactory cannot decode a frame out of a video container, so
 * video content URIs would decode-fail and render blank cells in the
 * staggered grid (only the duration chip of `MediaThumb` would show). This
 * class appends [VideoFrameDecoder.Factory] to the registry, making video
 * thumbnails decode their preview frame locally, as `build.gradle.kts`
 * promises.
 *
 * The manifest names this class via `android:name` on the `<application>`
 * tag; Android instantiates it before any activity, so every `AsyncImage`
 * finds it through `context.imageLoader`.
 *
 * Privacy (ADR-0006 / ADR-0014): [VideoFrameDecoder] decodes frames purely
 * on-device through `MediaMetadataRetriever`; `coil-video` is a decoder
 * artifact, not a network component, and this builder adds no network code.
 * The offline guarantee rests on the app never holding the INTERNET
 * permission --- enforced at build time by the merged-manifest privacy gate
 * and proven at runtime by `PrivacyEgressTest` --- which a locally built
 * `ImageLoader` cannot weaken.
 */
class ToadGalleryApplication : Application(), ImageLoaderFactory {

    /**
     * The app-wide loader used by every `AsyncImage`: the default registry
     * (`BitmapFactoryDecoder` + standard fetchers) plus
     * [VideoFrameDecoder.Factory] for video content URIs. GIF cells need no
     * extra decoder --- `BitmapFactoryDecoder` already decodes a GIF's first
     * frame, which is all a static thumbnail shows.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
