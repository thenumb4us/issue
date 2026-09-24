package com.issue.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {

    data class Response(
        val statusCode: Int,
        val body: String
    )

    fun get(
        path: String,
        bearerToken: String? = null
    ): Response {
        return request(
            method = "GET",
            path = path,
            body = null,
            bearerToken = bearerToken
        )
    }

    fun post(
        path: String,
        body: JSONObject,
        bearerToken: String? = null
    ): Response {
        return request(
            method = "POST",
            path = path,
            body = body,
            bearerToken = bearerToken
        )
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        bearerToken: String?
    ): Response {
        val url = URL(ApiConfig.BASE_URL + path)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/json")

            if (!bearerToken.isNullOrBlank()) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $bearerToken"
                )
            }

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )

                connection.outputStream.use { stream ->
                    stream.write(
                        body.toString()
                            .toByteArray(Charsets.UTF_8)
                    )
                }
            }

            val status = connection.responseCode

            val responseStream =
                if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val responseBody =
                responseStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: ""

            return Response(
                statusCode = status,
                body = responseBody
            )
        } finally {
            connection.disconnect()
        }
    }
}
