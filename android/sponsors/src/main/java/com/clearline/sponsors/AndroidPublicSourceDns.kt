package com.clearline.sponsors

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import com.clearline.core.AppError
import com.clearline.core.ClearLineException
import com.clearline.core.ErrorCode
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Android 10+ has a native cancellable DNS API; older supported OSes use one bounded worker. */
internal object AndroidPublicSourceDns : PublicSourceDns {
    private val legacyExecutor by lazy {
        ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4), { runnable ->
            Thread(runnable, "clearline-source-dns").apply { isDaemon = true }
        }).apply { allowCoreThreadTimeOut(true) }
    }

    override suspend fun resolve(host: String): List<InetAddress> = if (Build.VERSION.SDK_INT >= 29) modernResolve(host) else legacyResolve(host)

    @android.annotation.TargetApi(29)
    private suspend fun modernResolve(host: String): List<InetAddress> = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        try {
            DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_NO_RETRY, Executor { it.run() }, cancellation,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (continuation.isActive) {
                            if (rcode == 0) continuation.resume(answer)
                            else continuation.resumeWithException(dnsError())
                        }
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        if (continuation.isActive) continuation.resumeWithException(dnsError())
                    }
                })
        } catch (_: Exception) {
            if (continuation.isActive) continuation.resumeWithException(dnsError())
        }
    }

    private suspend fun legacyResolve(host: String): List<InetAddress> = suspendCancellableCoroutine { continuation ->
        try {
            val work = legacyExecutor.submit {
                if (continuation.isActive) try {
                    val answer = InetAddress.getAllByName(host).toList()
                    if (continuation.isActive) continuation.resume(answer)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(dnsError())
                }
            }
            // Some older system resolvers ignore thread interruption. The caller
            // still cancels promptly and this single bounded worker cannot grow.
            continuation.invokeOnCancellation { work.cancel(true); legacyExecutor.purge() }
        } catch (_: RejectedExecutionException) {
            continuation.resumeWithException(dnsError())
        }
    }

    private fun dnsError() = ClearLineException(AppError(ErrorCode.NETWORK_UNAVAILABLE, "Public source DNS could not be verified.", true))
}
