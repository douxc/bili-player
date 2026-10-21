package dev.nfish.biliaudio.playback

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Expose the service's part navigation as standard system/notification media controls. */
@UnstableApi
class PartSessionPlayer(player: Player, private val parts: PartPlayback) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): State {
        val state = super.getState()
        val current = parts.state.value
        val commands = state.availableCommands.buildUpon()
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_NEXT)
            .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, current.hasPrevious)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, current.hasPrevious)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, current.hasNext)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, current.hasNext)
            .build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }

    fun refreshCommands() { invalidateState() }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> parts.previous()
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> parts.next()
            else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        // The actual audio item resolves asynchronously in PartPlayback. Refresh the buttons now
        // so a rapid second press uses the new part's boundaries, even while it is loading.
        invalidateState()
        return Futures.immediateVoidFuture()
    }
}
