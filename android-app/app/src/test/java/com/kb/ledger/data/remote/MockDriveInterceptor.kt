package com.kb.ledger.data.remote

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Test OkHttp interceptor that allows enqueueing canned responses or custom response handlers.
 */
class MockDriveInterceptor : Interceptor {

    private val handlers = mutableListOf<(Request) -> Response?>()
    val interceptedRequests = mutableListOf<Request>()

    fun handle(handler: (Request) -> Response?) {
        handlers.add(handler)
    }

    fun clear() {
        handlers.clear()
        interceptedRequests.clear()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        interceptedRequests.add(request)

        for (handler in handlers) {
            val response = handler(request)
            if (response != null) return response
        }

        return createResponse(
            request = request,
            code = 404,
            body = """{"error": {"code": 404, "message": "No mock handler matched request: ${request.url}"}}"""
        )
    }

    companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun createResponse(
            request: Request,
            code: Int,
            body: String = "",
            message: String = if (code in 200..299) "OK" else "Error"
        ): Response {
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(message)
                .body(body.toResponseBody(JSON_MEDIA_TYPE))
                .build()
        }
    }
}
