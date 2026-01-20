const val DEFAULT_CANVAS_PAGE = "https://canvas.sydney.edu.au"

data class CourseUser(
    val userId: Long,
    val name: String,
    val unikey: String,
    val studentNumber: String,
    val sections: List<String>,
    val roles: List<String>,
    val profileUrl: String,
    val inactive: Boolean
) {
    fun isStudent() = roles.all { role -> role == "Student" }

    fun active() = !inactive
}