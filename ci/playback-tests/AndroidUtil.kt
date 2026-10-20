package android.util
import java.io.File
import java.io.FileOutputStream
object Log {
    fun w(tag: String, message: String) = println("$tag: $message")
    fun e(tag: String, message: String) = println("$tag: $message")
}
// Contract stub, NOT a test of Android's real AtomicFile implementation/fsync.
class AtomicFile(private val target: File) {
    private val pending get() = File(target.path + ".new")
    fun startWrite() = FileOutputStream(pending)
    fun finishWrite(stream: FileOutputStream) { stream.close(); check(pending.renameTo(target)) }
    fun failWrite(stream: FileOutputStream) { stream.close(); pending.delete() }
    fun openRead() = target.inputStream()
}
