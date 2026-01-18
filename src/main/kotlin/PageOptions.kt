import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitUntilState

object PageOptions {
    fun waitUntilState(state: WaitUntilState = WaitUntilState.LOAD): Page.NavigateOptions =
        Page.NavigateOptions().setWaitUntil(state)

    fun selector(timeout: Double): Page.WaitForSelectorOptions =
        Page.WaitForSelectorOptions().setTimeout(timeout)

    fun click(timeout: Double): Page.ClickOptions =
        Page.ClickOptions().setTimeout(timeout)

    fun loadState(timeout: Double): Page.WaitForLoadStateOptions =
        Page.WaitForLoadStateOptions().setTimeout(timeout)
}