import kotlin.properties.Delegates.notNull

class AutomationState {
    lateinit var edEmail: String
    lateinit var courseId: String
    lateinit var courseCode: String
    lateinit var courseUsers: List<CourseUser>
    lateinit var edStartDate: String
    var edYear by notNull<Int>()
}