package com.indicvision.semper.data.net

import java.net.URLEncoder

/**
 * Every page of one of the backend's cursor-paged listings (`page` in the
 * response, `page_token` in the request; `backend/app/routers/_shared.py`).
 *
 * [fetch] is called with null for the first page and then with each
 * `nextPageToken` until the backend stops naming one. A token seen before ends
 * the walk: the backend restarts from the beginning when the document a token
 * names has been deleted, and that must not become an endless loop.
 */
internal inline fun <P> fetchAllPages(fetch: (pageToken: String?) -> P, pageOf: (P) -> PageDto?): List<P> {
    val pages = mutableListOf<P>()
    val seen = mutableSetOf<String>()
    var token: String? = null
    do {
        val page = fetch(token)
        pages += page
        token = pageOf(page)?.takeIf { it.hasMore }?.nextPageToken?.takeIf { it.isNotBlank() && seen.add(it) }
    } while (token != null)
    return pages
}

/** `?page_token=…` for a page after the first, else "" (the first page's URL is the bare route). */
internal fun pageTokenQuery(pageToken: String?): String =
    if (pageToken == null) "" else "?page_token=" + URLEncoder.encode(pageToken, Charsets.UTF_8.name())

/** One manifest from its pages: the first page's session fields, every page's files. */
@JvmName("mergedFiles")
internal fun List<SessionFilesResponse>.merged(): SessionFilesResponse =
    first().copy(files = flatMap { it.files }, page = null)

/** One upload plan from its pages: the first page's session state, every page's pending files. */
@JvmName("mergedUploads")
internal fun List<SessionUploadsResponse>.merged(): SessionUploadsResponse =
    first().copy(uploads = flatMap { it.uploads }, page = null)
