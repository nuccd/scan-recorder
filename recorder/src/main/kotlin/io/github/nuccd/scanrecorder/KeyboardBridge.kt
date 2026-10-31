package io.github.nuccd.scanrecorder

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

internal enum class KeyboardConnection(val label: Int) {
  OFF(R.string.keyboard_off),
  WAITING(R.string.keyboard_waiting),
  CONNECTED(R.string.keyboard_connected),
  PAUSED(R.string.keyboard_paused),
  ERROR(R.string.keyboard_connection_error)
}

/** A loopback-only channel. Sends are explicit; reconnects never replay records. */
internal class KeyboardBridge(private val onChange: () -> Unit) {
  @Volatile var state = KeyboardConnection.OFF
    private set

  @Volatile private var session: Session? = null

  @Synchronized fun setActive(active: Boolean) {
    if (active && session == null) {
      val current = Session()
      session = current
      report(current, KeyboardConnection.WAITING)
      Thread({ serve(current) }, "keyboard-usb").apply { isDaemon = true }.start()
    } else if (!active) {
      val previous = session
      session = null
      previous?.close()
      state = KeyboardConnection.OFF
      onChange()
    }
  }

  fun send(value: String): KeyboardDelivery {
    if (!KeyboardProtocol.canSend(value)) return KeyboardDelivery.BLOCKED
    val current = session
    val channel = current?.channel
    return if (current == null || channel == null || state != KeyboardConnection.CONNECTED) {
      KeyboardDelivery.PENDING
    } else {
      send(current, channel, value)
    }
  }

  private fun send(current: Session, channel: Channel, value: String): KeyboardDelivery = synchronized(channel) {
    if (session !== current || current.channel !== channel || state != KeyboardConnection.CONNECTED) {
      KeyboardDelivery.PENDING
    } else {
      val id = UUID.randomUUID().toString()
      try {
        val result = KeyboardProtocol.result(id, channel.exchange(KeyboardProtocol.frame(id, value)))
        if (result == KeyboardDelivery.UNCERTAIN) {
          channel.close()
          report(current, KeyboardConnection.WAITING)
        }
        result
      } catch (_: IOException) {
        channel.close()
        report(current, KeyboardConnection.WAITING)
        KeyboardDelivery.UNCERTAIN
      }
    }
  }

  private fun serve(current: Session) {
    try {
      val server = synchronized(this) {
        if (session !== current) return
        ServerSocket().apply {
          current.server = this
          reuseAddress = true
          bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), KeyboardProtocol.PORT))
        }
      }
      while (session === current) {
        val socket = server.accept().apply {
          soTimeout = 2500
          tcpNoDelay = true
        }
        val channel = Channel(socket)
        current.channel = channel
        try {
          checkClient(current, channel)
        } catch (_: IOException) {
          report(current, KeyboardConnection.WAITING)
        } finally {
          channel.close()
          current.channel = null
        }
      }
    } catch (_: IOException) {
      report(current, KeyboardConnection.ERROR)
    } finally {
      current.close()
    }
  }

  private fun checkClient(current: Session, channel: Channel) {
    if (channel.exchange(KeyboardProtocol.HELLO) != KeyboardProtocol.READY) throw IOException("Protocol mismatch")
    while (session === current) {
      synchronized(channel) {
        val reply = channel.exchange("PING")
        val next = when (reply) {
          "PONG ACTIVE" -> KeyboardConnection.CONNECTED
          "PONG PAUSED" -> KeyboardConnection.PAUSED
          else -> throw IOException("Invalid heartbeat")
        }
        report(current, next)
      }
      Thread.sleep(500)
    }
  }

  private fun report(current: Session, next: KeyboardConnection) {
    if (session === current && state != next) {
      state = next
      onChange()
    }
  }

  private class Session {
    @Volatile var server: ServerSocket? = null

    @Volatile var channel: Channel? = null
    fun close() {
      runCatching { server?.close() }
      channel?.close()
    }
  }

  private class Channel(private val socket: Socket) {
    private val reader: BufferedReader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
    private val writer: BufferedWriter = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
    fun exchange(line: String): String {
      writer.write(line)
      writer.write("\n")
      writer.flush()
      val result = StringBuilder()
      while (result.length < 128) {
        val next = reader.read()
        if (next == -1) throw IOException("Disconnected")
        if (next == 10) break
        result.append(next.toChar())
      }
      if (result.length >= 128) throw IOException("Reply too long")
      return result.toString()
    }
    fun close() {
      runCatching { socket.close() }
    }
  }
}