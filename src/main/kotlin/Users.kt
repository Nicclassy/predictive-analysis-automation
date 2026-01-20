import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException

class CourseUsersFinder(private val page: Page) {
    fun findUsers(courseId: String): List<CourseUser> {
        page.navigate("https://canvas.sydney.edu.au/courses/$courseId/users")
        page.waitForSelector("tr.rosterUser")

        val rows = page.locator("tr.rosterUser")

        var previousCount = rows.count()

        while (true) {
            page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)")
            val loadedRows = try {
                page.waitForFunction(
                    """
                    (prev) => {
                        return document.querySelectorAll('tr.rosterUser').length > prev;
                    }
                    """,
                    previousCount,
                    Page.WaitForFunctionOptions().setTimeout(10000.0)
                )
                true
            } catch (_: PlaywrightException) {
                false
            }

            val currentCount = rows.count()
            if (!loadedRows || currentCount == previousCount)
                break

            previousCount = currentCount
        }

        return (0 until rows.count()).map { i ->
            parseUser(rows.nth(i))
        }
    }

    private fun parseUser(row: Locator): CourseUser {
        val idAttr = row.getAttribute("id")
            ?: error("Row missing id attribute")

        val userId = idAttr.removePrefix("user_").toLong()

        val nameAnchor = row.locator("a.roster_user_name")

        val name = nameAnchor.innerText().trim()
        val profileUrl = nameAnchor.getAttribute("href")
            ?: error("Profile URL not found")

        val cells = row.locator("td")

        val unikey = cells.nth(2).innerText().trim()
        val studentNumber = cells.nth(3).innerText().trim()

        val sections = row
            .locator("td[data-testid='section-column-cell'] .section")
            .allInnerTexts()
            .map { it.trim() }

        val roles = cells.nth(5)
            .locator("div")
            .allInnerTexts()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val inactive = row
            .locator("span.label")
            .allInnerTexts()
            .any { it.equals("inactive", ignoreCase = true) }

        return CourseUser(
            userId,
            name,
            unikey,
            studentNumber,
            sections,
            roles,
            profileUrl,
            inactive
        )
    }
}