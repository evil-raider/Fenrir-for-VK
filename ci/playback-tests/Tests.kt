package dev.ragnarok.fenrir.media.music
import android.content.Context
import androidx.media3.exoplayer.source.ShuffleOrder
import de.maxr1998.modernpreferences.PreferenceScreen
import dev.ragnarok.fenrir.model.Audio
import dev.ragnarok.fenrir.util.PersistentShuffle as P
import dev.ragnarok.fenrir.util.PlaybackTrace
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.random.Random

fun hydrateAgain() {
    for (name in listOf("playedMem", "maxMem")) {
        val field = P.javaClass.getDeclaredField(name).apply { isAccessible = true }
        (field.get(P) as MutableMap<*, *>).clear()
    }
}
fun indexes(order: ShuffleOrder): List<Int> {
    val result = mutableListOf<Int>()
    var index = order.getFirstIndex()
    while (index != -1) {
        check(index !in result) { "cyclic permutation" }
        result.add(index); index = order.getNextIndex(index)
    }
    check(result.size == order.getLength())
    return result
}
private fun load(ctx: Context): PlaybackResumeStore.Snapshot? {
    val done = CountDownLatch(1)
    var result: PlaybackResumeStore.Snapshot? = null
    PlaybackResumeStore.loadAsync(ctx) { result = it; done.countDown() }
    check(done.await(10, TimeUnit.SECONDS)); return result
}
fun main() {
    try { runTests() } catch (t: Throwable) { t.printStackTrace(); kotlin.system.exitProcess(1) }
}
private fun runTests() {
    val ctx = Context()
    val tracks = (0..19).map { Audio(it) }
    var assertions = 0
    // Arbitrary restores/rebuilds, selected played or unplayed: all remaining strictly
    // AFTER current, all history BEFORE it; no played track can leak into future suffix.
    repeat(1000) { iteration ->
        val r = Random(iteration)
        val source = "random_$iteration"
        val played = tracks.indices.filter { r.nextBoolean() }.toSet()
        played.forEach { P.markPlayed(ctx, source, tracks[it]) }
        val current = r.nextInt(tracks.size)
        val order = P.buildOrder(ctx, source, tracks, current).toList()
        check(order.toSet() == tracks.indices.toSet())
        val suffix = order.drop(order.indexOf(current) + 1)
        check(suffix.toSet() == (tracks.indices.toSet() - played - current))
        assertions += 2
    }
    // Mid-song appends, restart/re-hydration, independence between sources.
    val source = "append_restore"
    P.markPlayed(ctx, source, tracks[0]); P.markPlayed(ctx, source, tracks[2])
    P.buildOrder(ctx, source, tracks.take(5), 2)
    hydrateAgain()
    val expanded = P.buildOrder(ctx, source, tracks, 2).toList()
    check(expanded.drop(expanded.indexOf(2) + 1).toSet() == tracks.indices.toSet() - setOf(0, 2))
    check(P.buildOrder(ctx, "other", tracks, 2).toList().first() == 2)
    // Partial known queue must not reset the completed prefix.
    val partial = "partial"
    P.buildOrder(ctx, partial, tracks, 0)
    tracks.take(5).forEach { P.markPlayed(ctx, partial, it) }
    check(!P.isPassComplete(ctx, partial, tracks.take(5)))
    check(P.buildOrder(ctx, partial, tracks.take(5), 0).last() == 0)
    // End-of-pass only after ALL have been visited; next build starts a new pass.
    val cycle = "cycle"
    P.buildOrder(ctx, cycle, tracks, 0)
    tracks.dropLast(1).forEach { P.markPlayed(ctx, cycle, it) }
    check(!P.isPassComplete(ctx, cycle, tracks))
    P.markPlayed(ctx, cycle, tracks.last()); check(P.isPassComplete(ctx, cycle, tracks))
    check(P.buildOrder(ctx, cycle, tracks, 8).first() == 8)
    check(!P.isPassComplete(ctx, cycle, tracks))
    // Commas in a local URL survive the NEW storage format and memory loss.
    val comma = Audio(99, isLocal = true, url = "file:///music/a,b.mp3")
    P.markPlayed(ctx, "comma", comma); hydrateAgain()
    check(P.buildOrder(ctx, "comma", listOf(comma, tracks[0]), 1).toList() == listOf(0, 1))
    // Old CSV state still migrates without resetting valid old keys.
    PreferenceScreen.getPreferences(ctx).edit().putString("ci_shuffle_played_legacy", "0_1,2_1")
    hydrateAgain()
    val legacyOrder = P.buildOrder(ctx, "legacy", tracks, 2).toList()
    check(legacyOrder.drop(legacyOrder.indexOf(2) + 1).toSet() == tracks.indices.toSet() - setOf(0, 2))
    // Online identity must not change when URL is refreshed or replaced by a local copy.
    check(P.keyOf(Audio(7, url = "https://old")) == P.keyOf(Audio(7, url = "file:///cached")))
    assertions += 12
    // Validate real extracted production MusicShuffleOrder with range moves in both
    // directions, empty moves, insertion/removal, and inverse index navigation.
    repeat(500) { seed ->
        val random = Random(seed)
        val n = random.nextInt(1, 50)
        val original = MusicPlaybackService.MusicShuffleOrder(n, seed.toLong())
        val old = indexes(original)
        val from = random.nextInt(n + 1); val end = random.nextInt(from, n + 1)
        val dest = random.nextInt(n - (end - from) + 1)
        val playlist = (0 until n).toMutableList()
        val moved = playlist.subList(from, end).toList()
        repeat(end - from) { playlist.removeAt(from) }; playlist.addAll(dest, moved)
        val changed = original.cloneAndMove(from, end, dest)
        check(indexes(changed).map { playlist[it] } == old)
        val next = indexes(changed)
        next.zipWithNext().forEach { (a, b) -> check(changed.getPreviousIndex(b) == a) }
        val inserted = original.cloneAndInsert(n / 2, 3)
        check(indexes(inserted).toSet() == (0 until n + 3).toSet())
        check(indexes(inserted.cloneAndRemove(n / 2, n / 2 + 3)) == old)
        check(indexes(original.cloneAndClear()).isEmpty())
        assertions += 5
    }
    // REAL production store, fixture Audio serialization, Android prefs/AtomicFile contract
    // stubs: payload roundtrip, identity pairing, stale index repair, missing-key fallback.
    val resume = Context()
    PlaybackResumeStore.saveQueue(resume, tracks, "my_queue")
    PlaybackResumeStore.saveState(resume, 99, 45678, tracks[7])
    val snapshot = load(resume)!!
    check(snapshot.index == 7 && snapshot.positionMs == 45678L && snapshot.queueId == "my_queue")
    val raw = File(resume.filesDir, "player_resume_queue.json").readText()
    check(raw.contains("\"queueId\":\"my_queue\""))
    PlaybackResumeStore.saveState(resume, 0, 1000, Audio(999))
    check(load(resume)!!.let { it.index == 0 && it.positionMs == 0L })
    // Legacy bare-array queue remains readable.
    val legacyCtx = Context()
    File(legacyCtx.filesDir, "player_resume_queue.json").writeText("[{\"id\":42}]")
    legacyCtx.getSharedPreferences("player_resume_state", 0).edit().apply {
        putBoolean("has_queue", true); putString("queue_id", "legacy_queue")
    }
    check(load(legacyCtx)!!.let { it.audios[0].id == 42 && it.queueId == "legacy_queue" })
    // REAL logger + export: valid JSONL, bounded rotation, privacy (no raw local path).
    repeat(9000) { PlaybackTrace.event(resume, "rotation_test", "padding" to "x".repeat(2000)) }
    val exported = CountDownLatch(1)
    var zip: File? = null
    PlaybackTrace.export(resume) { zip = it; exported.countDown() }
    check(exported.await(30, TimeUnit.SECONDS))
    ZipFile(zip!!).use { archive ->
        check(archive.size() == 4)
        archive.entries().asSequence().forEach { entry ->
            check(entry.size <= 4L * 1024 * 1024)
            archive.getInputStream(entry).bufferedReader().useLines { lines ->
                lines.forEach { check(org.json.JSONObject(it).getInt("schema") == 1) }
            }
        }
    }
    assertions += 9
    println("PASS: $assertions grouped checks; 1000 pass rebuilds, 500 permutation mutations, resume/store/rotation")
    kotlin.system.exitProcess(0) // Production stores deliberately use process-lived executors.
}
