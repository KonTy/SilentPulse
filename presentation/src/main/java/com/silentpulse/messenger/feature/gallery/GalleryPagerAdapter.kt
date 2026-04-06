/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
@file:Suppress("DEPRECATION")
package com.silentpulse.messenger.feature.gallery

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.ui.PlayerView
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.mms.ContentType
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkRealmAdapter
import com.silentpulse.messenger.common.base.QkViewHolder
import com.silentpulse.messenger.extensions.isImage
import com.silentpulse.messenger.extensions.isVideo
import com.silentpulse.messenger.model.MmsPart
import com.silentpulse.messenger.util.GlideApp
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import java.util.*
import javax.inject.Inject

class GalleryPagerAdapter @Inject constructor(private val context: Context) : QkRealmAdapter<MmsPart>() {

    companion object {
        private const val VIEW_TYPE_INVALID = 0
        private const val VIEW_TYPE_IMAGE = 1
        private const val VIEW_TYPE_VIDEO = 2
    }

    val clicks: Subject<View> = PublishSubject.create()

    private val contentResolver = context.contentResolver
    private val exoPlayers = Collections.newSetFromMap(WeakHashMap<ExoPlayer?, Boolean>())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return QkViewHolder(when (viewType) {
            VIEW_TYPE_IMAGE -> inflater.inflate(R.layout.gallery_image_page, parent, false).apply {

                // When calling the public setter, it doesn't allow the midscale to be the same as the
                // maxscale or the minscale. We don't want 3 levels and we don't want to modify the library
                // so let's celebrate the invention of reflection!
                val imageView = this.findViewById<PhotoView>(R.id.image)
                val attacher = imageView.attacher
                val attacherClass = attacher.javaClass
                attacherClass.getDeclaredField("mMinScale").run {
                    isAccessible = true
                    setFloat(attacher, 1f)
                }
                attacherClass.getDeclaredField("mMidScale").run {
                    isAccessible = true
                    setFloat(attacher, 1f)
                }
                attacherClass.getDeclaredField("mMaxScale").run {
                    isAccessible = true
                    setFloat(attacher, 3f)
                }
            }

            VIEW_TYPE_VIDEO -> inflater.inflate(R.layout.gallery_video_page, parent, false)

            else -> inflater.inflate(R.layout.gallery_invalid_page, parent, false)

        }.apply { setOnClickListener(clicks::onNext) })
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        val part = getItem(position) ?: return
        when (getItemViewType(position)) {
            VIEW_TYPE_IMAGE -> {
                // We need to explicitly request a gif from glide for animations to work
                val imageView = holder.itemView.findViewById<PhotoView>(R.id.image)
                when (part.getUri().let(contentResolver::getType)) {
                    ContentType.IMAGE_GIF -> GlideApp.with(context)
                            .asGif()
                            .load(part.getUri())
                            .into(imageView)

                    else -> GlideApp.with(context)
                            .asBitmap()
                            .load(part.getUri())
                            .into(imageView)
                }
            }

            VIEW_TYPE_VIDEO -> {
                val exoPlayer = ExoPlayer.Builder(context).build()
                holder.itemView.findViewById<PlayerView>(R.id.video).player = exoPlayer
                exoPlayers.add(exoPlayer)

                val videoSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                        .createMediaSource(MediaItem.fromUri(part.getUri()))
                exoPlayer.setMediaSource(videoSource)
                exoPlayer.prepare()
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val part = getItem(position)
        return when {
            part?.isImage() == true -> VIEW_TYPE_IMAGE
            part?.isVideo() == true -> VIEW_TYPE_VIDEO
            else -> VIEW_TYPE_INVALID
        }
    }

    fun destroy() {
        exoPlayers.forEach { exoPlayer -> exoPlayer?.release() }
    }

}
