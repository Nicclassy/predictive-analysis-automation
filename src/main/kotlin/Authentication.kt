import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.options.LoadState
import io.github.cdimascio.dotenv.dotenv
import java.nio.ByteBuffer
import org.apache.commons.codec.binary.Base32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.Path

const val DEFAULT_TIMEOUT: Double = 3000.0
const val LONG_TIMEOUT: Double = 7000.0
const val SSO_URL = "https://sso.sydney.edu.au"

val STATE_PATH = Path("state.json")

private fun hotpTokenFromSecret(secret: String, intervalsNo: Long): String {
    val key = Base32().decode(secret.uppercase())
    val msg = ByteBuffer.allocate(8).putLong(intervalsNo).array()

    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(key, "HmacSHA1"))

    val hash = mac.doFinal(msg)
    val offset = hash[19].toInt() and 0x0F

    val binary =
        ((hash[offset].toInt() and 0x7F) shl 24) or
        ((hash[offset + 1].toInt() and 0xFF) shl 16) or
        ((hash[offset + 2].toInt() and 0xFF) shl 8) or
        (hash[offset + 3].toInt() and 0xFF)

    val otp = binary % 1_000_000
    return otp.toString().padStart(6, '0')
}

private fun totpTokenFromSecret(secret: String): String {
    val intervalsNo = System.currentTimeMillis() / 1000
    return hotpTokenFromSecret(secret, intervalsNo / 30)
}

fun login(page: Page) {
    val env = dotenv()

    val unikey = env["UNIKEY"]
    val password = env["PASSWORD"]
    val totpSecret = env["TOTP_SECRET"]

    require(unikey.isNotBlank())
    require(password.isNotBlank())
    require(totpSecret.isNotBlank())

    while (!page.url().startsWith(SSO_URL))
        Thread.sleep(100)

    page.waitForSelector(
        "[name='identifier']",
        PageOptions.selector(DEFAULT_TIMEOUT)
    )
    page.fill("[name='identifier']", unikey)
    page.click(
        "input.button[type='submit']",
        PageOptions.click(LONG_TIMEOUT)
    )

    page.waitForSelector(
        "[name='credentials.passcode']",
        PageOptions.selector(DEFAULT_TIMEOUT)
    )
    page.fill("[name='credentials.passcode']", password)
    page.click(
        "input.button[type='submit']",
        PageOptions.click(LONG_TIMEOUT)
    )

    try {
        page.waitForSelector(
            "[aria-label='Select Google Authenticator.']",
            PageOptions.selector(DEFAULT_TIMEOUT)
        )
        page.click(
            "[aria-label='Select Google Authenticator.']",
            PageOptions.click(DEFAULT_TIMEOUT)
        )
    } catch (_: PlaywrightException) {}

    page.waitForSelector(
        "[name='credentials.passcode']",
        PageOptions.selector(DEFAULT_TIMEOUT)
    )

    val token = totpTokenFromSecret(totpSecret)
    page.fill("[name='credentials.passcode']", token)
    page.click(
        "input.button[type='submit']",
        PageOptions.click(LONG_TIMEOUT)
    )
}
private fun waitForAuthCookies(
    context: BrowserContext,
    timeoutMs: Long = 10_000
) {
    val deadline = System.currentTimeMillis() + timeoutMs

    while (System.currentTimeMillis() < deadline) {
        val cookies = context.cookies()

        if (cookies
            .any {
                it.domain.contains("canvas.sydney.edu.au")
                && it.name.contains("session", ignoreCase = true)
            }
        ) {
            return
        }

        Thread.sleep(100)
    }

    error("Authentication cookies not detected")
}


fun ensureLoggedIn(
    page: Page,
    context: BrowserContext,
    url: String
) {
    page.navigate(
        url,
        PageOptions.waitUntilState()
    )

    repeat(5) {
        try {
            page.waitForLoadState(
               LoadState.NETWORKIDLE,
                PageOptions.loadState(LONG_TIMEOUT)
            )
            return@repeat
        } catch (_: PlaywrightException) {
            Thread.sleep((DEFAULT_TIMEOUT / 10).toLong())
        }
    }

    if (!page.isClosed &&
        page.url().isNotEmpty() &&
        !page.url().startsWith("https://")
    ) {
        error("Page did not load properly")
    }

    if (!page.url().startsWith(SSO_URL)) {
        return
    }

    login(page)
    page.waitForURL(url)

    context.storageState(
        BrowserContext.StorageStateOptions().setPath(STATE_PATH)
    )
}