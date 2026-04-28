/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.tile

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.annotation.StringRes
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.CONTENT_SCALE_MODE_CROP
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_BOLD
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.wearapp.R
import io.element.android.wearapp.bridge.WearBridgeCacheStore
import io.element.android.wearapp.ui.common.toInitials
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_TILE_ROOMS = 7
private const val AVATAR_SIZE_DP = 40f
private const val AVATAR_GAP_DP = 4f
private const val TILE_BACKGROUND_RADIUS_DP = 100f
private const val TITLE_SPACING_DP = 6f

enum class ConversationTileMode(
    @StringRes val labelRes: Int,
    @StringRes val emptyRes: Int,
    @StringRes val unavailableRes: Int,
    val resourceKey: String,
) {
    RECENT(
        labelRes = R.string.tile_recent_conversations_label,
        emptyRes = R.string.tile_recent_conversations_empty,
        unavailableRes = R.string.tile_recent_conversations_unavailable,
        resourceKey = "recent-conversations",
    ) {
        override fun matches(room: WatchFavoriteRoom): Boolean = !room.isFavorite
    },
    FAVORITES(
        labelRes = R.string.tile_favorite_conversations_label,
        emptyRes = R.string.tile_favorite_conversations_empty,
        unavailableRes = R.string.tile_favorite_conversations_unavailable,
        resourceKey = "favorite-conversations",
    ) {
        override fun matches(room: WatchFavoriteRoom): Boolean = room.isFavorite
    };

    abstract fun matches(room: WatchFavoriteRoom): Boolean
}

/**
 * Cached, avatar-first tile implementation.
 *
 * The tile reads the latest room and avatar state from the Wear app cache so it can render even
 * while the bridge is reconnecting. The layout intentionally avoids scroll-dependent content: it
 * is just a honeycomb of conversations.
 */
abstract class ConversationTileServiceBase : TileService() {
    protected abstract val tileMode: ConversationTileMode

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val tile = runCatching {
            val snapshot = readTileSnapshot()

            TileBuilders.Tile.Builder()
                .setResourcesVersion(snapshot.resourcesVersion)
                .setFreshnessIntervalMillis(60_000L)
                .setTileTimeline(Timeline.fromLayoutElement(buildLayout(snapshot.rooms)))
                .build()
        }.onFailure {
            Timber.e(it, "tile request failed")
        }.getOrElse {
            TileBuilders.Tile.Builder()
                .setResourcesVersion("conversation-tile-v4-fallback")
                .setTileTimeline(Timeline.fromLayoutElement(buildFallbackLayout()))
                .build()
        }

        return Futures.immediateFuture(tile)
    }

    override fun onTileAddEvent(requestParams: androidx.wear.tiles.EventBuilders.TileAddEvent) {
        Timber.d("tile added")
        TileService.getUpdater(this).requestUpdate(javaClass)
    }

    override fun onTileEnterEvent(requestParams: androidx.wear.tiles.EventBuilders.TileEnterEvent) {
        Timber.d("tile entered")
        TileService.getUpdater(this).requestUpdate(javaClass)
    }

    @Suppress("RETURN_TYPE_MISMATCH_ON_OVERRIDE")
    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val snapshot = runCatching { readTileSnapshot() }
            .onFailure { Timber.e(it, "tile resources request failed") }
            .getOrDefault(TileSnapshot())

        val resourcesBuilder = ResourceBuilders.Resources.Builder()
            .setVersion(snapshot.resourcesVersion)

        val avatarSizePx = avatarSizePx()
        snapshot.rooms.forEach { room ->
            resourcesBuilder.addIdToImageMapping(
                avatarResourceId(room.roomId),
                ResourceBuilders.ImageResource.Builder()
                    .setInlineResource(
                        ResourceBuilders.InlineImageResource.Builder()
                            .setData(renderAvatarPng(room = room, avatarBytes = snapshot.avatarBytes[room.roomId], sizePx = avatarSizePx))
                            .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                            .setWidthPx(avatarSizePx)
                            .setHeightPx(avatarSizePx)
                            .build(),
                    )
                    .build(),
            )
        }

        return Futures.immediateFuture(
            resourcesBuilder.build(),
        )
    }

    private fun buildLayout(rooms: List<WatchFavoriteRoom>): LayoutElement {
        return if (rooms.isEmpty()) buildEmptyLayout() else buildRoomsLayout(rooms)
    }

    private fun buildRoomsLayout(rooms: List<WatchFavoriteRoom>): LayoutElement {
        val rowSizes = honeycombRows(rooms.size)
        var cursor = 0
        val column = Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)

        column.addContent(titleText(getString(tileMode.labelRes)))
        column.addContent(Spacer.Builder().setHeight(dp(TITLE_SPACING_DP)).build())

        rowSizes.forEachIndexed { index, rowSize ->
            if (index > 0) {
                column.addContent(Spacer.Builder().setHeight(dp(AVATAR_GAP_DP)).build())
            }
            val rowRooms = rooms.subList(cursor, cursor + rowSize)
            cursor += rowSize
            column.addContent(avatarRow(rowRooms))
        }

        return container(column.build())
    }

    private fun buildEmptyLayout(): LayoutElement {
        return container(
            content = Column.Builder()
                .setWidth(expand())
                .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                .addContent(titleText(getString(tileMode.labelRes)))
                .addContent(Spacer.Builder().setHeight(dp(TITLE_SPACING_DP)).build())
                .addContent(textBody(getString(tileMode.emptyRes)))
                .build(),
            clickable = openAppClickable(),
        )
    }

    private fun buildFallbackLayout(): LayoutElement {
        return container(
            content = Column.Builder()
                .setWidth(expand())
                .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                .addContent(titleText(getString(tileMode.labelRes)))
                .addContent(Spacer.Builder().setHeight(dp(TITLE_SPACING_DP)).build())
                .addContent(textBody(getString(tileMode.unavailableRes)))
                .build(),
            clickable = openAppClickable(),
        )
    }

    private fun container(
        content: LayoutElement,
        clickable: Clickable? = null,
    ): LayoutElement {
        val modifiers = Modifiers.Builder()
            .setBackground(
                Background.Builder()
                    .setColor(argb(0xFF101317.toInt()))
                    .setCorner(Corner.Builder().setRadius(dp(TILE_BACKGROUND_RADIUS_DP)).build())
                    .build(),
            )
        clickable?.let(modifiers::setClickable)

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(modifiers.build())
            .addContent(content)
            .build()
    }

    private fun titleText(text: String): LayoutElement {
        return androidx.wear.protolayout.LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setMaxLines(1)
            .setFontStyle(
                androidx.wear.protolayout.LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(12f))
                    .setWeight(FONT_WEIGHT_BOLD)
                    .setColor(argb(0xFFE8EEF5.toInt()))
                    .build(),
            )
            .build()
    }

    private fun textBody(text: String): LayoutElement {
        return androidx.wear.protolayout.LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setMaxLines(2)
            .setFontStyle(
                androidx.wear.protolayout.LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(12f))
                    .setColor(argb(0xFFB8C1CC.toInt()))
                    .build(),
            )
            .build()
    }

    private fun avatarRow(rowRooms: List<WatchFavoriteRoom>): LayoutElement {
        val row = Row.Builder()
            .setWidth(wrap())
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
        rowRooms.forEachIndexed { index, room ->
            if (index > 0) {
                row.addContent(Spacer.Builder().setWidth(dp(AVATAR_GAP_DP)).build())
            }
            row.addContent(avatarImage(room))
        }
        return Box.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(row.build())
            .build()
    }

    private fun avatarImage(room: WatchFavoriteRoom): LayoutElement {
        return Image.Builder()
            .setResourceId(avatarResourceId(room.roomId))
            .setWidth(dp(AVATAR_SIZE_DP))
            .setHeight(dp(AVATAR_SIZE_DP))
            .setContentScaleMode(CONTENT_SCALE_MODE_CROP)
            .setModifiers(
                Modifiers.Builder()
                    .setClickable(openRoomClickable(room.roomId))
                    .build(),
            )
            .build()
    }

    private fun openRoomClickable(roomId: String): Clickable {
        return Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(ComponentName(packageName, "io.element.android.wearapp.ui.WearMainActivity").className)
                            .addKeyToExtraMapping("roomId", ActionBuilders.stringExtra(roomId))
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun openAppClickable(): Clickable {
        return Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(ComponentName(packageName, "io.element.android.wearapp.ui.WearMainActivity").className)
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun readTileSnapshot(): TileSnapshot = runBlocking {
        val cacheStore = WearBridgeCacheStore(this@ConversationTileServiceBase)
        val rooms = cacheStore.readFavorites()
            .filter(tileMode::matches)
            .sortedByDescending { it.lastActivityTsMs }
            .distinctBy { it.roomId }
            .take(MAX_TILE_ROOMS)
        val avatarBytes = cacheStore.readAvatars(rooms.map { it.roomId })
        TileSnapshot(
            rooms = rooms,
            avatarBytes = avatarBytes,
            resourcesVersion = buildResourcesVersion(rooms, avatarBytes),
        )
    }

    private fun buildResourcesVersion(
        rooms: List<WatchFavoriteRoom>,
        avatarBytes: Map<String, ByteArray>,
    ): String {
        val seed = rooms.joinToString(separator = "|") { room ->
            val avatarHash = avatarBytes[room.roomId]?.contentHashCode() ?: 0
            "${room.roomId}:${room.lastActivityTsMs}:$avatarHash"
        }
        return "${tileMode.resourceKey}-v4-${seed.hashCode().toUInt().toString(16)}"
    }

    private fun renderAvatarPng(room: WatchFavoriteRoom, avatarBytes: ByteArray?, sizePx: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val decodedAvatar = avatarBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

        if (decodedAvatar != null) {
            val shader = BitmapShader(decodedAvatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val matrix = Matrix()
            val scale = max(sizePx / decodedAvatar.width.toFloat(), sizePx / decodedAvatar.height.toFloat())
            val dx = (sizePx - decodedAvatar.width * scale) / 2f
            val dy = (sizePx - decodedAvatar.height * scale) / 2f
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            shader.setLocalMatrix(matrix)
            canvas.drawOval(bounds, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        } else {
            canvas.drawOval(
                bounds,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = avatarPalette(room.displayName) },
            )
            drawInitials(canvas = canvas, room = room, sizePx = sizePx)
        }

        val borderWidth = sizePx * 0.04f
        val inset = borderWidth / 2f
        canvas.drawOval(
            RectF(inset, inset, sizePx - inset, sizePx - inset),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x30FFFFFF
                style = Paint.Style.STROKE
                strokeWidth = borderWidth
            },
        )

        return bitmap.toPngByteArray()
    }

    private fun drawInitials(canvas: Canvas, room: WatchFavoriteRoom, sizePx: Int) {
        val initials = room.displayName.toInitials()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx * if (initials.length > 1) 0.38f else 0.48f
        }
        val baseline = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initials, sizePx / 2f, baseline, textPaint)
    }

    private fun avatarSizePx(): Int = (AVATAR_SIZE_DP * resources.displayMetrics.density)
        .roundToInt()
        .coerceAtLeast(56)

    private fun honeycombRows(count: Int): List<Int> = when (count.coerceIn(0, MAX_TILE_ROOMS)) {
        0 -> emptyList()
        1 -> listOf(1)
        2 -> listOf(2)
        3 -> listOf(1, 2)
        4 -> listOf(1, 2, 1)
        5 -> listOf(2, 1, 2)
        6 -> listOf(2, 2, 2)
        else -> listOf(2, 3, 2)
    }

    private fun avatarResourceId(roomId: String): String = "avatar_${roomId.hashCode().toUInt().toString(16)}"

    private fun Bitmap.toPngByteArray(): ByteArray = ByteArrayOutputStream().use { output ->
        compress(Bitmap.CompressFormat.PNG, 100, output)
        output.toByteArray()
    }

    companion object {
        private val PALETTE = intArrayOf(
            0xFF5B8DEF.toInt(),
            0xFF8E64FF.toInt(),
            0xFF1FA37A.toInt(),
            0xFFE07A2D.toInt(),
            0xFFC94F7C.toInt(),
            0xFF4C9BCE.toInt(),
        )

        private fun avatarPalette(seed: String): Int {
            val raw = seed.hashCode().mod(PALETTE.size)
            val index = if (raw < 0) raw + PALETTE.size else raw
            return PALETTE[index]
        }
    }

    private data class TileSnapshot(
        val rooms: List<WatchFavoriteRoom> = emptyList(),
        val avatarBytes: Map<String, ByteArray> = emptyMap(),
        val resourcesVersion: String = "conversation-tile-v4-empty",
    )
}

class RecentContactsTileService : ConversationTileServiceBase() {
    override val tileMode: ConversationTileMode = ConversationTileMode.RECENT
}
