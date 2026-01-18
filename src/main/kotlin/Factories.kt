import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright

object PlaywrightFactory {
    fun create(): Playwright {
        return Playwright.create()
    }

    fun createBrowser(playwright: Playwright, headless: Boolean = false): Browser {
        return playwright
            .chromium()
            .launch(BrowserType.LaunchOptions().setHeadless(headless))
    }
}

object BrowserContextFactory {
    fun create(browser: Browser): BrowserContext {
        return browser.newContext(
            Browser.NewContextOptions()
                .setStorageStatePath(
                    if (STATE_PATH.toFile().exists()) STATE_PATH else null
                )
        )
    }
}