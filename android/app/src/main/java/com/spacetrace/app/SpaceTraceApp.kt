package com.spacetrace.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class SpaceTraceApp : Application(), ImageLoaderFactory {

    // Registers video-frame decoding on the app-wide default Coil loader so every
    // AsyncImage/SubcomposeAsyncImage in the app (file thumbnails included) can pull a
    // frame out of a local video file, not just decode still images.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
