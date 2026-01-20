import com.microsoft.playwright.Frame
import com.microsoft.playwright.Page

fun Page.findFrame(
    timeoutMs: Long = 30_000,
    pollIntervalMs: Long = 1_000,
    predicate: (Frame) -> Boolean
): Frame {
    val deadline = System.currentTimeMillis() + timeoutMs

    while (System.currentTimeMillis() < deadline) {
        val frame = frames().firstOrNull(predicate)
        if (frame != null) {
            return frame
        }

        waitForTimeout(pollIntervalMs.toDouble())
    }

    error("Timed out after ${timeoutMs}ms waiting for matching frame")
}