import com.microsoft.playwright.*
import com.microsoft.playwright.options.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.exists

private sealed class DownloadResult

private object RecordingAlreadyDownloaded : DownloadResult()
private data class DownloadedRecording(val recordingName: String, val path: Path) : DownloadResult()

private fun findEchoVideoFrame(page: Page): Frame =
    page.findFrame {
        it.name().startsWith("tool_content") && it.url() != "about:blank"
    }

private fun downloadAllVideoAnalytics(
    courseId: String, page: Page, downloadFolder: Path, isDownloaded: (String) -> Boolean
): List<DownloadedRecording> {
    page.navigate("https://canvas.sydney.edu.au/courses/$courseId/external_tools/11653")
    page.waitForLoadState(LoadState.DOMCONTENTLOADED)

    var topIndex = 0
    val results = mutableListOf<DownloadResult>()
    while (true) {
        val frame = findEchoVideoFrame(page)
        frame.waitForSelector("div.contents-wrapper > .class-row")

        val rows = frame.locator("div.contents-wrapper > .class-row")
        val total = rows.count()

        if (topIndex >= total)
            break

        val row = rows.nth(topIndex)
        val className = row.getAttribute("class") ?: ""

        if (className.contains("group")) {
            results.addAll(
                processRecordingsList(page, topIndex, downloadFolder, isDownloaded)
            )
        } else {
            results.add(
                processRecording(page, downloadFolder, isDownloaded, topIndex)
            )
        }

        topIndex += 1
    }

    return results.mapNotNull { it as? DownloadedRecording }
}

private fun processRecordingsList(
    page: Page,
    listIndex: Int,
    downloadFolder: Path,
    isDownloaded: (String) -> Boolean,
): List<DownloadResult> {
    val results = mutableListOf<DownloadResult>()
    var recordingIndex = 0

    while (true) {
        val frame = findEchoVideoFrame(page)
        val row = frame
            .locator("div.contents-wrapper > .class-row")
            .nth(listIndex)

        val opener = row.locator("button.opener")
        if (opener.count() == 0)
            error("Opener is empty")

        opener.first().click()

        val recordingsList = frame
            .locator("div.contents-wrapper > .class-row")
            .nth(listIndex)

        val children = recordingsList.locator("> .class-row")
        val childCount = children.count()

        if (recordingIndex >= childCount)
            break

        val result = processRecording(
            page,
            downloadFolder,
            isDownloaded,
            listIndex,
            recordingIndex,
        )
        results.add(result)

        recordingIndex++
    }

    return results
}

private fun processRecording(
    page: Page,
    downloadFolder: Path,
    isDownloaded: (String) -> Boolean,
    topIndex: Int,
    recordingIndex: Int? = null
): DownloadResult {
    val frame = findEchoVideoFrame(page)

    val row =
        if (recordingIndex == null) {
            frame
                .locator("div.contents-wrapper > .class-row")
                .nth(topIndex)
        } else {
            frame
                .locator("div.contents-wrapper > .class-row")
                .nth(topIndex)
                .locator("> .class-row")
                .nth(recordingIndex)
        }

    val recordingName = row.textContent()
    if (isDownloaded(recordingName))
        return RecordingAlreadyDownloaded

    val menuButton =
        row.locator("div.courseMediaIndicator[data-test-id='open-class-video-menu']")

    if (menuButton.count() == 0)
        error("No menu buttons found")

    menuButton.first().click()

    val detailsItem =
        row.locator("a[data-test-id='class-media-details']")

    detailsItem.waitFor(
        Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE)
    )

    detailsItem.first().click()

    val detailsFrame = findEchoVideoFrame(page)
    detailsFrame.waitForSelector("#details-tab")

    val analyticsButton =
        detailsFrame.locator(
            "#details-tab button",
            Frame.LocatorOptions().setHasText("Analytics")
        )

    if (analyticsButton.count() == 0)
        error("No analytics button found")

    analyticsButton.first().click()

    val analyticsFrame = findEchoVideoFrame(page)
    selectAnalyticsLast120Days(analyticsFrame)

    val result = downloadAnalyticsCsv(analyticsFrame, page, recordingName, downloadFolder)
    page.reload()
    page.waitForLoadState(LoadState.DOMCONTENTLOADED)
    return result
}

private fun selectAnalyticsLast120Days(analyticsFrame: Frame) {
    analyticsFrame.dispatchEvent(
        "#analyticsDateRangeSelect_input .echo-select__control",
        "mousedown"
    )

    val input = analyticsFrame.locator("#react-select-3-input")
    repeat(2) {
        input.press("ArrowDown", Locator.PressOptions().setTimeout(5000.0))
    }

    input.press("Enter", Locator.PressOptions().setTimeout(5000.0))
}

private fun downloadAnalyticsCsv(
    frame: Frame,
    page: Page,
    recordingName: String,
    destinationFolder: Path
): DownloadedRecording {
    val downloadButton = frame.getByRole(
        AriaRole.BUTTON,
        Frame.GetByRoleOptions().setName("Download Data")
    )

    val download = page.waitForDownload {
        downloadButton.click()
    }

    val destination = destinationFolder.resolve(download.suggestedFilename())

    if (!destinationFolder.exists())
        destinationFolder.createDirectory()

    download.saveAs(destination)
    return DownloadedRecording(recordingName, destination)
}

object Echo360VideoAnalytics {
    private val downloadsFolder = Paths.get("echo")
    private val downloadsInfo = downloadsFolder.resolve("downloads.json")

    fun exists(recordingName: String): Boolean {
        val file = downloadsInfo.toFile()
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        return json[recordingName] != null
    }

    fun download(courseId: String, page: Page) {
        if (!downloadsFolder.exists())
            downloadsFolder.createDirectory()

        if (!downloadsInfo.exists()) {
            downloadsInfo.createFile()
            val file = downloadsInfo.toFile()
            val json = buildJsonObject {}
            file.writeText(Json {}.encodeToString(json))
        }

        val file = downloadsInfo.toFile()
        var json = Json.parseToJsonElement(file.readText()).jsonObject

        val downloads = downloadAllVideoAnalytics(
            courseId, page,downloadsFolder, ::exists
        )
        for (download in downloads) {
            val (recordingName, destination) = download
            json = buildJsonObject {
                json.forEach { (k, v) -> put(k, v) }
                put(recordingName, destination.toString())
            }
        }

        file.writeText(Json { prettyPrint = true }.encodeToString(json))
    }
}