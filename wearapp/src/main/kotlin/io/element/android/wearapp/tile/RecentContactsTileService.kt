/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.tile

import android.content.ComponentName
import android.util.Log
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_BOLD
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_MEDIUM
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.element.android.watchbridge.contract.WatchDataPaths
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchBridgeSerialization
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.wearapp.WearApp
import io.element.android.wearapp.ui.common.toInitials
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/**
 * Wear OS Tile that shows recent DM contacts in a honeycomb-like grid on the watch face carousel.
 * Tapping any contact avatar opens the main app.
 */
class RecentContactsTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        Log.d(TAG, "onTileRequest called, lastClickableId=${requestParams.currentState.lastClickableId}")
        return try {
            val bridge = (application as WearApp).bridgeClient
            // Try current in-memory snapshot first — show all favorites.
            var contacts = bridge.favorites.value
                .asSequence()
                .sortedByDescending { it.lastActivityTsMs }
                .distinctBy { it.roomId }
                .take(7)
                .toList()

            // If empty, read directly from the Wear Data Layer (blocking).
            if (contacts.isEmpty()) {
                contacts = readFavoritesFromDataLayer()
                    .asSequence()
                    .sortedByDescending { it.lastActivityTsMs }
                    .distinctBy { it.roomId }
                    .take(7)
                    .toList()
                Log.d(TAG, "tile: read ${contacts.size} contacts from Data Layer fallback")
            } else {
                Log.d(TAG, "tile: using ${contacts.size} contacts from in-memory cache")
            }

            // If still empty, trigger a phone refresh so next tile update has data.
            if (contacts.isEmpty()) {
                bridge.refreshPhoneReachability()
            }

            val layout = if (contacts.isEmpty()) {
                buildEmptyLayout()
            } else {
                buildHoneycombLayout(contacts)
            }

            val tile = TileBuilders.Tile.Builder()
                .setResourcesVersion("1")
                .setTileTimeline(Timeline.fromLayoutElement(layout))
                .setFreshnessIntervalMillis(5 * 60 * 1000L) // refresh every 5 min
                .build()

            Futures.immediateFuture(tile)
        } catch (e: Exception) {
            Log.e(TAG, "onTileRequest FAILED", e)
            // Return a minimal error tile instead of crashing.
            val errorLayout = Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
                .addContent(
                    Text.Builder()
                        .setText("Error loading tile")
                        .setFontStyle(FontStyle.Builder().setSize(sp(12f)).setColor(argb(0xFFFF6666.toInt())).build())
                        .build(),
                )
                .build()
            Futures.immediateFuture(
                TileBuilders.Tile.Builder()
                    .setResourcesVersion("1")
                    .setTileTimeline(Timeline.fromLayoutElement(errorLayout))
                    .build(),
            )
        }
    }

    override fun onTileEnterEvent(requestParams: androidx.wear.tiles.EventBuilders.TileEnterEvent) {
        Log.d(TAG, "onTileEnterEvent — tile became visible")
    }

    override fun onTileLeaveEvent(requestParams: androidx.wear.tiles.EventBuilders.TileLeaveEvent) {
        Log.d(TAG, "onTileLeaveEvent — tile no longer visible")
    }

    override fun onTileAddEvent(requestParams: androidx.wear.tiles.EventBuilders.TileAddEvent) {
        Log.d(TAG, "onTileAddEvent — tile added to carousel")
    }

    override fun onTileRemoveEvent(requestParams: androidx.wear.tiles.EventBuilders.TileRemoveEvent) {
        Log.d(TAG, "onTileRemoveEvent — tile removed from carousel")
    }

    /**
     * Directly reads the favorites DataItem from the Wear Data Layer.
     * This is a blocking call, used when the in-memory cache hasn't been populated yet.
     */
    private fun readFavoritesFromDataLayer(): List<WatchFavoriteRoom> {
        return try {
            val dataClient = Wearable.getDataClient(this)
            val items = runBlocking {
                dataClient.getDataItems(android.net.Uri.parse("wear:${WatchDataPaths.FAVORITES}")).await()
            }
            val result = mutableListOf<WatchFavoriteRoom>()
            for (index in 0 until items.count) {
                val item = items[index]
                val bytes = DataMapItem.fromDataItem(item).dataMap.getByteArray("envelope") ?: continue
                val envelope = WatchBridgeSerialization.decodeEnvelopeFromBytes(bytes)
                val payload = envelope.payload
                if (payload is WatchSync.FavoritesSnapshot) {
                    result.addAll(payload.rooms)
                }
            }
            items.release()
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read favorites from Data Layer", e)
            emptyList()
        }
    }

    @Suppress("RETURN_TYPE_MISMATCH_ON_OVERRIDE")
    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion("1")
                .build(),
        )
    }

    private fun buildEmptyLayout(): LayoutElement {
        val launchAppClickable = Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(
                                ComponentName(packageName, "io.element.android.wearapp.ui.WearMainActivity").className,
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setClickable(launchAppClickable)
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(0xFF1B1B1B.toInt()))
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                Column.Builder()
                    .setWidth(wrap())
                    .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        Text.Builder()
                            .setText("Recent contacts")
                            .setFontStyle(
                                FontStyle.Builder()
                                    .setSize(sp(14f))
                                    .setColor(argb(0xFFCCCCCC.toInt()))
                                    .setWeight(FONT_WEIGHT_MEDIUM)
                                    .build(),
                            )
                            .build(),
                    )
                    .addContent(Spacer.Builder().setHeight(dp(8f)).build())
                    .addContent(
                        Text.Builder()
                            .setText("Open app to sync")
                            .setFontStyle(
                                FontStyle.Builder()
                                    .setSize(sp(12f))
                                    .setColor(argb(0xFF999999.toInt()))
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun buildHoneycombLayout(contacts: List<WatchFavoriteRoom>): LayoutElement {
        val launchAppClickable = Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(
                                ComponentName(packageName, "io.element.android.wearapp.ui.WearMainActivity").className,
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

        val column = Column.Builder()
            .setWidth(wrap())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setClickable(launchAppClickable)
                    .build(),
            )

        // Title
        column.addContent(
            Text.Builder()
                .setText("Recent contacts")
                .setFontStyle(
                    FontStyle.Builder()
                        .setSize(sp(14f))
                        .setColor(argb(0xFFCCCCCC.toInt()))
                        .setWeight(FONT_WEIGHT_MEDIUM)
                        .build(),
                )
                .build(),
        )

        column.addContent(Spacer.Builder().setHeight(dp(8f)).build())

        // Honeycomb rows: 2 / 3 / 2
        val row1 = contacts.take(2)
        val row2 = contacts.drop(2).take(3)
        val row3 = contacts.drop(5).take(2)

        if (row1.isNotEmpty()) {
            column.addContent(buildContactRow(row1))
            column.addContent(Spacer.Builder().setHeight(dp(4f)).build())
        }
        if (row2.isNotEmpty()) {
            column.addContent(buildContactRow(row2))
            column.addContent(Spacer.Builder().setHeight(dp(4f)).build())
        }
        if (row3.isNotEmpty()) {
            column.addContent(buildContactRow(row3))
        }

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setClickable(launchAppClickable)
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(0xFF1B1B1B.toInt()))
                            .build(),
                    )
                    .build(),
            )
            .addContent(column.build())
            .build()
    }

    private fun buildContactRow(contacts: List<WatchFavoriteRoom>): LayoutElement {
        val row = Row.Builder()
            .setWidth(wrap())

        contacts.forEachIndexed { index, contact ->
            if (index > 0) {
                row.addContent(Spacer.Builder().setWidth(dp(6f)).build())
            }
            row.addContent(buildContactBadge(contact))
        }

        return row.build()
    }

    private fun buildContactBadge(contact: WatchFavoriteRoom): LayoutElement {
        val color = avatarPalette(contact.displayName)
        val initials = contact.displayName.toInitials()

        return Box.Builder()
            .setWidth(dp(42f))
            .setHeight(dp(42f))
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(color))
                            .setCorner(Corner.Builder().setRadius(dp(21f)).build())
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                Text.Builder()
                    .setText(initials)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(14f))
                            .setColor(argb(0xFFFFFFFF.toInt()))
                            .setWeight(FONT_WEIGHT_BOLD)
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    companion object {
        private const val TAG = "RecentContactsTile"

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
}
