package com.lmusic.download

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed implementation of NewPipeExtractor's Downloader interface.
 * NewPipe uses this to make all HTTP requests to YouTube.
 */
object NewPipeDownloaderImpl : Downloader() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(
        request: org.schabi.newpipe.extractor.downloader.Request
    ): Response {
        val builder = Request.Builder().url(request.url())

        // Default User-Agent helps avoid YouTube 403s
        builder.header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )

        // Copy request headers
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (v in values) builder.addHeader(name, v)
        }

        // Body, if any
        val body = request.dataToSend()
        if (body != null && body.isNotEmpty()) {
            builder.method(request.httpMethod(), body.toRequestBody())
        } else if (request.httpMethod() != "GET") {
            builder.method(request.httpMethod(), null)
        }

        val resp = client.newCall(builder.build()).execute()
        val responseBody = resp.body?.string() ?: ""
        val headers = resp.headers.toMultimap()
        return Response(resp.code, resp.message, headers, responseBody, resp.request.url.toString())
    }
}
