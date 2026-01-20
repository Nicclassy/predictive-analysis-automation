import com.microsoft.playwright.Frame
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitForSelectorState
import com.microsoft.playwright.options.WaitUntilState
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.io.path.createDirectory
import kotlin.io.path.isDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

private fun navigateToProfileUrl(page: Page, user: CourseUser) {
    page.navigate(
        user.profileUrl,
        PageOptions.waitUntilState(WaitUntilState.NETWORKIDLE)
    )
}

private fun navigateToCourseAnalytics(page: Page) {
    val locator = page.locator("xpath=//*[@id=\"right_nav\"]//a[contains(., 'Course Analytics')]")
    locator.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))

    val url = locator.getAttribute("href")
    requireNotNull(url) { "User does not have course analytics url" }

    page.navigate(
        url,
        Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE)
    )
}

private fun goToWeeklyOnlineActivity(page: Page): Frame {
    val analyticsFrame = page.findFrame {
        frame -> frame.url().contains("canvas-analytics-syd-prod.inscloudgate.net")
    }

    analyticsFrame.waitForLoadState(LoadState.NETWORKIDLE)
    analyticsFrame.waitForSelector(
        "div[role='tablist']",
        Frame.WaitForSelectorOptions().setTimeout(15000.0)
    )

    val tabLocator = analyticsFrame.locator("#tab-svActivityTab")

    tabLocator.waitFor(
        Locator.WaitForOptions()
            .setState(WaitForSelectorState.VISIBLE)
            .setTimeout(10000.0)
    )

    tabLocator.click()

    analyticsFrame.locator("#svActivityTab")
        .waitFor(Locator.WaitForOptions().setTimeout(10000.0))
    return analyticsFrame
}

fun extractCSVFromZip(zipPath: Path, destinationFolder: Path): Path {
    val zipFile = ZipFile(zipPath.toFile())
    val entry = zipFile.entries().asSequence().firstOrNull()
        ?: throw IllegalStateException("Zip is empty")

    val baseName = zipPath.fileName.toString().removeSuffix(".zip")
    val outputFile = destinationFolder.resolve("$baseName.csv")
    zipFile.getInputStream(entry).use { input ->
        outputFile.toFile().outputStream().use { output ->
            input.copyTo(output)
        }
    }

    zipFile.close()
    zipPath.deleteIfExists()
    return outputFile
}

private fun downloadOnlineActivity(
    user: CourseUser,
    page: Page,
    analyticsFrame: Frame,
    downloadFolder: Path
): Path {
    val downloadButton = analyticsFrame.locator(
        "xpath=//button[@aria-label='Download CSV' or @data-pendo='ca-download-csv-button']"
    )

    downloadButton.waitFor(
        Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE)
    )

    val download = page.waitForDownload {
        downloadButton.click()
    }

    val path = Paths.get(downloadFolder.toString(), "${user.unikey}.zip")
    download.saveAs(path)
    return extractCSVFromZip(path, downloadFolder)
}

object CourseAnalytics {
    private val downloadFolder = Paths.get("analytics")

    fun exists(user: CourseUser): Boolean =
        Paths.get(downloadFolder.toString(), "${user.unikey}.csv").exists()

    fun download(user: CourseUser, page: Page): Path {
        if (!downloadFolder.exists())
            downloadFolder.createDirectory()

        require(downloadFolder.isDirectory()) { "Path is not directory" }
        navigateToProfileUrl(page, user)
        navigateToCourseAnalytics(page)
        val analyticsFrame = goToWeeklyOnlineActivity(page)
        return downloadOnlineActivity(user, page, analyticsFrame, downloadFolder)
    }
}