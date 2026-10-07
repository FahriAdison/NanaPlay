package com.opencloudgaming.opennow

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * 1.0.28 — defeats YouTube's non-Range request throttling.
 *
 * Root cause of "online songs never play" (verified with curl, Oct 2026):
 * YouTube's videoplayback servers throttle HTTP requests WITHOUT a `Range`
 * header to ~30KB/s (effectively stalling). ExoPlayer's initial progressive
 * request uses position=0/length=UNSET, which sends no Range header, so
 * playback stalls forever and the user perceives "tidak bisa play".
 * The SAME URL with any `Range` header streams at ~900KB/s.
 *
 * This wraps [DefaultHttpDataSource] and converts full-length opens
 * (position=0, length=UNSET) into explicit `Range: bytes=0-<huge>` requests.
 * YouTube replies 206 with the real content length; reads terminate at
 * actual EOF. Seeks already carry Range headers and are untouched.
 *
 * Only applied to YouTube/googlevideo hosts; everything else (local files,
 * other hosts) behaves exactly as before.
 */
@UnstableApi
internal class NanaRangedHttpDataSourceFactory(
    private val userAgent: String,
) : DataSource.Factory {

    private val upstream = DefaultHttpDataSource.Factory()
        .setUserAgent(userAgent)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setAllowCrossProtocolRedirects(true)

    override fun createDataSource(): DataSource {
        val inner = upstream.createDataSource()
        return object : DataSource by inner {
            override fun open(dataSpec: DataSpec): Long {
                return inner.open(maybeForceRange(dataSpec))
            }

            private fun maybeForceRange(dataSpec: DataSpec): DataSpec {
                if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) return dataSpec
                if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) return dataSpec
                val host = dataSpec.uri.host?.lowercase().orEmpty()
                val isYoutube = host.contains("googlevideo.com")
                    || host.contains("youtube.com")
                    || host.contains("youtu.be")
                if (!isYoutube) return dataSpec
                // bytes=0-<huge>: server answers 206 with the true length.
                // position + length - 1 stays within Long range (no overflow).
                return dataSpec.buildUpon()
                    .setPosition(0)
                    .setLength(Long.MAX_VALUE)
                    .build()
            }
        }
    }
}
