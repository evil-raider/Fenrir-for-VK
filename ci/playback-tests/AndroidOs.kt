package android.os
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) { fun post(action: Runnable) { action.run() } }
object SystemClock { fun elapsedRealtime() = System.nanoTime() / 1_000_000 }
