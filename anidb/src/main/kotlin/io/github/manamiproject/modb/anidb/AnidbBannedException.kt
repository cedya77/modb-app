package io.github.manamiproject.modb.anidb

/**
 * Thrown if the AniDB API answers with `<error code="500">banned</error>`.
 * The API sends the ban with http status 200 and without any information on how long it lasts.
 * @since 20.0.0
 * @param message Description including the anime ID which was requested.
 */
public class AnidbBannedException(message: String) : RuntimeException(message)
