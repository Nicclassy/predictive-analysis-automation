import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright

typealias BrowserAutomationAction = (AutomationState, Page, BrowserContext) -> Unit
typealias PageAutomationAction = (AutomationState, Page) -> Unit
typealias StatefulAction = (AutomationState) -> Unit

enum class ActionKind {
    BROWSER,
    PAGE,
    STATEFUL
}

class AutomationExecutor {
    private val pageActions = mutableListOf<PageAutomationAction>()
    private val browserActions = mutableListOf<BrowserAutomationAction>()
    private val statefulActions = mutableListOf<StatefulAction>()
    private val actionKinds = mutableListOf<ActionKind>()

    fun execute(perform: BrowserAutomationAction): AutomationExecutor {
        browserActions.add(perform)
        actionKinds.add(ActionKind.BROWSER)
        return this
    }

    fun execute(perform: PageAutomationAction): AutomationExecutor {
        pageActions.add(perform)
        actionKinds.add(ActionKind.PAGE)
        return this
    }

    fun execute(perform: StatefulAction): AutomationExecutor {
        statefulActions.add(perform)
        actionKinds.add(ActionKind.STATEFUL)
        return this
    }

    fun run(run: Boolean = true) {
        if (!run) return

        val playwright: Playwright = PlaywrightFactory.create()
        val browser: Browser = PlaywrightFactory.createBrowser(playwright)
        val browserContext: BrowserContext = BrowserContextFactory.create(browser)

        var statefulIndex = 0
        var pageIndex = 0
        var browserIndex = 0

        val page = browserContext.newPage()
        val state = AutomationState()

        try {
            for (taskKind in actionKinds) {
                when (taskKind) {
                    ActionKind.BROWSER -> {
                        val task = browserActions[browserIndex++]
                        task(state, page, browserContext)
                    }
                    ActionKind.PAGE -> {
                        val task = pageActions[pageIndex++]
                        task(state, page)
                    }
                    ActionKind.STATEFUL -> {
                        val task = statefulActions[statefulIndex++]
                        task(state)
                    }
                }
            }
        } finally {
            page.close()
            browserContext.close()
            browser.close()
            playwright.close()
        }
    }
}