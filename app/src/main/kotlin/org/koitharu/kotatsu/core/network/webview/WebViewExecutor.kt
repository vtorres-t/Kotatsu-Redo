package org.koitharu.kotatsu.core.network.webview

import android.content.Context
import android.util.AndroidRuntimeException
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.proxy.ProxyProvider
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.util.ext.configureForParser
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.sanitizeHeaderValue
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Singleton
class WebViewExecutor @Inject constructor(
	@ApplicationContext private val context: Context,
	private val proxyProvider: ProxyProvider,
	private val cookieJar: MutableCookieJar,
	private val mangaRepositoryFactoryProvider: Provider<MangaRepository.Factory>,
) {

	private var webViewCached: WeakReference<WebView>? = null
	private val mutex = Mutex()

	val defaultUserAgent: String? by lazy {
		try {
			WebSettings.getDefaultUserAgent(context)
		} catch (e: AndroidRuntimeException) {
			e.printStackTraceDebug()
			// Probably WebView is not available
			null
		}
	}

    suspend fun evaluateJs(baseUrl: String?, script: String, timeoutMs: Long = 10000L): String? = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val webView = obtainWebView()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            try {
                webView.stopLoading()
                webView.clearHistory()

                if (!baseUrl.isNullOrEmpty()) {
                    println("DEBUG: Starting page load for $baseUrl")

                    val result = suspendCoroutine<String?> { cont ->
                        var hasResumed = false
                        var isPageFullyLoaded = false
                        val startTime = System.currentTimeMillis()

                        val contentChecker = object : Runnable {
                            override fun run() {
                                if (hasResumed) return

                                if (!isPageFullyLoaded) {
                                    println("DEBUG: Waiting for page to finish loading...")
                                    handler.postDelayed(this, 300)
                                    return
                                }

                                println("DEBUG: Checking script result...")

                                webView.evaluateJavascript(script) { result ->
                                    if (hasResumed) return@evaluateJavascript

                                    println("DEBUG: Raw result length: ${result?.length ?: 0}")

                                    // Check if script explicitly returned null (waiting for content)
                                    val isWaitingForContent = result == null || result == "null"

                                    if (isWaitingForContent) {
                                        println("DEBUG: Script returned null, content not ready yet")

                                        if (System.currentTimeMillis() - startTime >= timeoutMs) {
                                            println("DEBUG: Timeout reached")
                                            hasResumed = true
                                            handler.removeCallbacksAndMessages(null)
                                            cont.resume(null)
                                        } else {
                                            // Keep checking every 300ms
                                            handler.postDelayed(this, 300)
                                        }
                                    } else {
                                        // Script returned actual content - return it immediately
                                        println("DEBUG: Script detected valid content, returning immediately")
                                        hasResumed = true
                                        handler.removeCallbacksAndMessages(null)
                                        cont.resume(result.takeUnless { it == "null" })
                                    }
                                }
                            }
                        }

                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)

                                if (url == "about:blank") {
                                    println("DEBUG: Ignoring about:blank")
                                    return
                                }

                                println("DEBUG: onPageFinished for $url")
                                isPageFullyLoaded = true

                                handler.removeCallbacks(contentChecker)
                                handler.postDelayed(contentChecker, 500)
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)

                                if (url == "about:blank") {
                                    return
                                }

                                println("DEBUG: onPageStarted for $url")
                                isPageFullyLoaded = false
                            }
                        }

                        val timeoutCallback = Runnable {
                            if (!hasResumed) {
                                println("ERROR: Overall timeout reached")
                                hasResumed = true
                                handler.removeCallbacksAndMessages(null)
                                cont.resume(null)
                            }
                        }
                        handler.postDelayed(timeoutCallback, timeoutMs)

                        println("DEBUG: Loading URL: $baseUrl")
                        webView.loadUrl(baseUrl)
                    }

                    println("DEBUG: Returning result with length: ${result?.length ?: 0}")
                    return@withContext result
                }

                // If no baseUrl, just evaluate script directly
                println("DEBUG: Evaluating script without loading URL")
                suspendCoroutine { cont ->
                    webView.evaluateJavascript(script) { result ->
                        cont.resume(result?.takeUnless { it == "null" })
                    }
                }
            } finally {
                handler.removeCallbacksAndMessages(null)
                webView.reset()
            }
        }
    }

    suspend fun tryResolveCaptcha(exception: CloudFlareException, timeout: Long): Boolean = mutex.withLock {
		runCatchingCancellable {
			withContext(Dispatchers.Main.immediate) {
				val webView = obtainWebView()
				try {
					exception.source.getUserAgent()?.let {
						webView.settings.userAgentString = it
					}
					withTimeout(timeout) {
						suspendCancellableCoroutine { cont ->
							webView.webViewClient = CaptchaContinuationClient(
								cookieJar = cookieJar,
								targetUrl = exception.url,
								continuation = cont,
							)
							webView.loadUrl(exception.url)
						}
					}
				} finally {
					webView.reset()
				}
			}
		}.onFailure { e ->
			exception.addSuppressed(e)
			e.printStackTraceDebug()
		}.isSuccess
	}

    @MainThread
    private fun obtainWebView(): WebView = webViewCached?.get() ?: WebView(context).also {
        it.configureForParser(null)
        webViewCached = WeakReference(it)
    }

	private fun MangaSource.getUserAgent(): String? {
		val repository = mangaRepositoryFactoryProvider.get().create(this) as? ParserMangaRepository
		return repository?.getRequestHeaders()?.get(CommonHeaders.USER_AGENT)
	}

    @MainThread
    fun getDefaultUserAgentSync() = runCatching {
        obtainWebView().settings.userAgentString.sanitizeHeaderValue().trim().nullIfEmpty()
    }.onFailure { e ->
        e.printStackTraceDebug()
    }.getOrNull()

	@MainThread
	private fun WebView.reset() {
		stopLoading()
		webViewClient = WebViewClient()
		settings.userAgentString = defaultUserAgent
		loadDataWithBaseURL(null, " ", "text/html", null, null)
		clearHistory()
	}
}
