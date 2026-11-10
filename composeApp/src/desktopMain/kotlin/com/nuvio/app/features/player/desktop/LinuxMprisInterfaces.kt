package com.nuvio.app.features.player.desktop

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface LinuxMprisRoot : DBusInterface {
    fun Raise()
    fun Quit()

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getCanQuit(): Boolean

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getCanRaise(): Boolean

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getHasTrackList(): Boolean

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getIdentity(): String

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getDesktopEntry(): String

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getSupportedUriSchemes(): List<String>

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getSupportedMimeTypes(): List<String>
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface LinuxMprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getPlaybackStatus(): String

    @DBusBoundProperty(access = DBusProperty.Access.READ_WRITE)
    fun getRate(): Double

    @DBusBoundProperty(access = DBusProperty.Access.READ_WRITE)
    fun setRate(value: Double)

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getMinimumRate(): Double

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getMaximumRate(): Double

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getMetadata(): Map<String, Variant<*>>

    @DBusBoundProperty(access = DBusProperty.Access.READ_WRITE)
    fun getVolume(): Double

    @DBusBoundProperty(access = DBusProperty.Access.READ_WRITE)
    fun setVolume(value: Double)

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getPosition(): Long

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getCanGoNext(): Boolean

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getCanGoPrevious(): Boolean

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getCanPlay(): Boolean

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getCanPause(): Boolean

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getCanSeek(): Boolean

    @DBusBoundProperty(access = DBusProperty.Access.READ)
    fun getCanControl(): Boolean

    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}
