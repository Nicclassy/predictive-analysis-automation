import com.microsoft.playwright.*
import com.microsoft.playwright.options.*
import org.jsoup.*
import org.jsoup.nodes.*
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectory
import kotlin.io.path.exists

private data class WeekRange(val start: LocalDate, val end: LocalDate, private val formatter: DateTimeFormatter) {
    val startDateInput: String
        get() = start.format(formatter)

    val endDateInput: String
        get() = end.format(formatter)

    val filename: String
        get() {
            val fileFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            return "${start.format(fileFormatter)}_to_${end.format(fileFormatter)}"
        }
}

private data class StudentAnalytics(
    val name: String,
    val views: Int,
    val threads: Int,
    val answers: Int,
    val comments: Int,
    val hearts: Int
) {
    fun hasActivity(): Boolean = views > 0 || threads > 0 || answers > 0 || comments > 0 || hearts > 0
}

private data class WeeklyAnalytics(
    val students: List<StudentAnalytics>,
    val weekRange: WeekRange
) {
    fun hasAnyActivity(): Boolean = students.any(StudentAnalytics::hasActivity)
}

private fun parseStudents(html: String, weekRange: WeekRange): WeeklyAnalytics {
    val doc: Document = Jsoup.parse("<table><tbody>$html</tbody></table>")
    val rows: List<Element> = doc.body().select("tbody tr")

    val students = rows.map { row ->
        val columns = row.select("td")
        StudentAnalytics(
            name = columns[0].text().trim(),
            views = columns[1].text().trim().toInt(),
            threads = columns[2].text().trim().toInt(),
            answers = columns[3].text().trim().toInt(),
            comments = columns[4].text().trim().toInt(),
            hearts = columns[5].text().trim().toInt()
        )
    }

    return WeeklyAnalytics(students, weekRange)
}

private fun generateWeekRanges(startDateStr: String, year: Int, pattern: String = "d MMM yyyy"): Sequence<WeekRange> {
    val formatter = DateTimeFormatter.ofPattern(pattern)
    var currentStart = LocalDate.parse(startDateStr, formatter)

    return sequence {
        while (currentStart.year == year) {
            var currentEnd = currentStart.plusDays(6)
            if (currentEnd.year != year)
                currentEnd = LocalDate.of(year, 12, 31)

            yield(WeekRange(currentStart, currentEnd, formatter))
            currentStart = currentEnd.plusDays(1)
        }
    }
}

private fun navigateToEdAnalytics(page: Page, courseCode: String) {
    val dashboardCourse = page.getByRole(
        AriaRole.LINK, Page.GetByRoleOptions().setName(courseCode).setExact(true)
    )
    dashboardCourse.click()

    val analyticsButton = page.getByRole(
        AriaRole.LINK,
        Page.GetByRoleOptions().setName("Analytics")
    )
    analyticsButton.click()
    page.waitForLoadState(LoadState.DOMCONTENTLOADED)
}

private fun configureCustomAnalytics(page: Page) {
    val contributorsLink = page.getByRole(
        AriaRole.LINK,
        Page.GetByRoleOptions().setName("Contributors")
    )
    contributorsLink.click()

    val select = page.locator("div.ed-select select.ed-focus-outline")
    select.selectOption(SelectOption().setLabel("Custom"))
}

private fun parseWeeklyAnalytics(page: Page, startDate: String, year: Int): List<WeeklyAnalytics> {
    val startDateInput = page.locator("input.dtfield-input[placeholder='Start Date']")
    val endDateInput = page.locator("input.dtfield-input[placeholder='End Date']")
    val studentsButton = page.locator(
        ".pills-item",
        Page.LocatorOptions().setHasText("Students")
    )

    val result = mutableListOf<WeeklyAnalytics>()
    for (weekRange in generateWeekRanges(startDate, year)) {
        startDateInput.fill(weekRange.startDateInput)
        endDateInput.fill(weekRange.endDateInput)
        studentsButton.click()

        page.waitForLoadState(LoadState.DOMCONTENTLOADED)
        val table = page.locator("table.analytics-table.antab-sortable")
        table.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED))

        val tableHtml = table.innerHTML()
        result.add(parseStudents(tableHtml, weekRange))
    }

    return result
}

private fun exportWeeklyAnalyticsToCsv(allAnalytics: List<WeeklyAnalytics>, destinationFolder: Path) {
    if (!destinationFolder.exists())
        destinationFolder.createDirectory()

    var hasStartedExporting = false
    for (analytics in allAnalytics) {
        if (!hasStartedExporting && analytics.hasAnyActivity())
            hasStartedExporting = true

        if (!hasStartedExporting)
            continue

        val filename = "${analytics.weekRange.filename}.csv"
        val file = destinationFolder.resolve(filename).toFile()

        file.bufferedWriter().use { writer ->
            writer.write("name,views,threads,answers,comments,hearts\n")
            analytics.students.forEach { student ->
                writer.write(
                    "\"${student.name}\",${student.views},${student.threads},${student.answers},${student.comments},${student.hearts}\n"
                )
            }
        }
    }
}

object EdWeeklyAnalytics {
    private val destinationFolder = Paths.get("edstem")
    private val analyticsPath = destinationFolder.resolve("analytics.csv")

    fun downloadWeekly(page: Page, courseCode: String, startDate: String, year: Int) {
        navigateToEdAnalytics(page, courseCode)
        downloadAnalyticsCsv(page)
        configureCustomAnalytics(page)

        val allAnalytics = parseWeeklyAnalytics(page, startDate, year)
        exportWeeklyAnalyticsToCsv(allAnalytics, destinationFolder)
    }

    private fun downloadAnalyticsCsv(page: Page) {
        if (analyticsPath.exists())
            return

        val downloadButton = page.locator("button:has-text('Analytics CSV')")
        val download = page.waitForDownload {
            downloadButton.click()
        }

        download.saveAs(analyticsPath)
    }
}