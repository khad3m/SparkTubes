package com.sparktube.app.ui.music

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast
import com.sparktube.app.R
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.Playlist
import com.sparktube.app.data.VideoEntry

/**
 * Shared "Add to Playlist" picker (v1.7.4). Music never had a way to file a
 * song into a playlist — this adds one consistent entry point used from the
 * Music tab track sheet and the Now Playing options sheet:
 *   - "+ New Playlist" creates a playlist and files the song straight in;
 *   - every existing playlist row toggles membership (add / remove).
 */
object PlaylistPicker {

    fun show(context: Context, entry: VideoEntry, title: String, uploader: String) {
        val playlists = LocalStore.playlists(context)
        val items = mutableListOf(
            ActionSheet.Item("+ New Playlist") {
                showNewPlaylistDialog(context) { created ->
                    LocalStore.addToPlaylist(context, created.id, entry)
                    Toast.makeText(
                        context,
                        context.getString(R.string.saved_to_playlist_fmt, created.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
        if (playlists.isEmpty()) {
            items += ActionSheet.Item(context.getString(R.string.playlist_sheet_empty))
        }
        playlists.forEach { playlist ->
            val added = playlist.items.any { it.url == entry.url }
            items += ActionSheet.Item(
                if (added) "${playlist.name}  ✓  (${playlist.items.size})" else "${playlist.name}  (${playlist.items.size})",
                accent = added
            ) {
                if (added) {
                    LocalStore.removeFromPlaylist(context, playlist.id, entry.url)
                    Toast.makeText(
                        context,
                        context.getString(R.string.removed_from_playlist_fmt, playlist.name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    LocalStore.addToPlaylist(context, playlist.id, entry)
                    Toast.makeText(
                        context,
                        context.getString(R.string.saved_to_playlist_fmt, playlist.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        ActionSheet.show(context, title, uploader, items)
    }

    /** Input dialog → create playlist → hand it back through [onCreated]. */
    fun showNewPlaylistDialog(context: Context, onCreated: (Playlist) -> Unit) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.new_playlist_hint)
            setSingleLine(true)
        }
        val container = android.widget.FrameLayout(context).apply {
            setPadding(24, 8, 24, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.new_playlist)
            .setView(container)
            .setPositiveButton(R.string.create_playlist) { dialog, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                dialog.dismiss()
                when {
                    name.isEmpty() -> Toast.makeText(
                        context, R.string.playlist_name_empty, Toast.LENGTH_SHORT
                    ).show()

                    else -> {
                        val created = LocalStore.createPlaylist(context, name)
                        if (created == null) {
                            Toast.makeText(
                                context, R.string.playlist_name_exists, Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            onCreated(created)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
