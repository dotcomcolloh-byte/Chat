package com.telefam.data.api

/** Joins a base URL and a possibly-relative path with exactly one slash. */
fun joinBaseUrl(base: String, path: String): String {
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    return base.trimEnd('/') + "/" + path.trimStart('/')
}
