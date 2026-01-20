import io.github.cdimascio.dotenv.dotenv
import kotlin.io.path.Path
import kotlin.io.path.exists

fun main() {
    val executor = AutomationExecutor()
    executor
        .execute { _, page, context ->
            ensureLoggedIn(page, context, DEFAULT_CANVAS_PAGE)
        }
        .execute { state ->
            val env = dotenv()
            state.courseId = env["COURSE_ID"]
            state.courseCode = env["COURSE_CODE"]
            state.edEmail = env["ED_EMAIL"]
        }
        .execute(false) { state, page ->
            val usersPath = Path("users.csv")
            val users: List<CourseUser>

            if (!usersPath.exists()) {
                val usersFinder = CourseUsersFinder(page)
                users = usersFinder.findUsers(state.courseId)
                CourseUser::class.exportCSV(users, usersPath)
            } else {
                users = loadCSV(usersPath) { record ->
                    CourseUser(
                        userId = record["userId"].toLong(),
                        name = record["name"],
                        unikey = record["unikey"],
                        studentNumber = record["studentNumber"],
                        sections = record["sections"].split(';'),
                        roles = record["roles"].split(';'),
                        profileUrl = record["profileUrl"],
                        inactive = record["inactive"].toBoolean()
                    )
                }
            }

            state.courseUsers = users
                .filter(CourseUser::isStudent)
                .filter(CourseUser::active)
        }
        .execute(false) { state, page ->
            for (user in state.courseUsers.take(state.courseUsers.size)) {
                if (!CourseAnalytics.exists(user)) {
                    val path = CourseAnalytics.download(user, page)
                    println("Downloaded to $path")
                } else {
                    println("${user.unikey} is already downloaded")
                }
            }
        }
        .execute(false) { state, page ->
            Echo360VideoAnalytics.download(state.courseId, page)
        }
        .execute { state ->
            val env = dotenv()
            state.edStartDate = env["ED_START_DATE"]
            state.edYear = env["ED_YEAR"].toInt()
        }
        .execute { state, page, context ->
            ensureEdLoggedIn(page, context, state.edEmail)

        }
        .execute { state, page ->
            EdWeeklyAnalytics.downloadWeekly(
                page, state.courseCode, state.edStartDate, state.edYear
            )
        }
        .run()
}