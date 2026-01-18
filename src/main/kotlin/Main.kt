import kotlin.io.path.Path
import kotlin.io.path.exists

fun main() {
    val executor = AutomationExecutor()
    executor
        .execute { state, page, context ->
            ensureLoggedIn(page, context, DEFAULT_CANVAS_PAGE)
            state.courseId = "62983"
        }
        .execute { state, page ->
            val usersPath = Path("users.csv")
            val users: List<CourseUser>

            if (!usersPath.exists()) {
                val usersFinder = CourseUsersFinder(page)
                users = usersFinder.findUsers(state.courseId)
                CourseUser::class.exportCSV(users, usersPath)
            } else {
                users = loadCSV(Path("users.csv")) { record ->
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
        .execute { state, page ->
            val user = state.courseUsers[0]
            if (!CourseAnalytics.exists(user)) {
                val path = CourseAnalytics.download(user, page)
                println("Downloaded to $path")
            } else {
                println("${user.unikey} is already downloaded")
            }
        }
        .execute { state, page ->
            Echo360VideoAnalytics.go(state.courseId, page)
        }
        .run()
}