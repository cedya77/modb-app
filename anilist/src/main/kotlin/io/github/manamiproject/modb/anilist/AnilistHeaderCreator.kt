package io.github.manamiproject.modb.anilist

import io.github.manamiproject.modb.core.httpclient.RequestBody

/**
 * Creates all necessary header parameter for a request to the official anilist GraphQL API.
 * @since 1.0.0
 */
public object AnilistHeaderCreator {

    /**
     * Creates all headers necessary for a request to the official anilist GraphQL API.
     * The public API doesn't require a session cookie or csrf token.
     * @since 1.0.0
     * @param requestBody The request body being sent.
     * @return A [Map] of header parameter. Key is the name of the parameter itself and value is a list of values for this specific header parameter.
     */
    public fun createAnilistHeaders(requestBody: RequestBody): Map<String, List<String>> {
        return mapOf(
            "accept" to listOf("application/json"),
            "content-type" to listOf(requestBody.mediaType),
        )
    }
}
