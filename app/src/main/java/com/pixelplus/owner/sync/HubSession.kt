package com.pixelplus.owner.sync

/** All settings commits, queue confirmations and background hub requests share this lock. */
object HubSession {
    val lock = Any()
}
