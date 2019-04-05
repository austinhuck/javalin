/*
 * Javalin - https://javalin.io
 * Copyright 2017 David Åse
 * Licensed under Apache 2.0: https://github.com/tipsy/javalin/blob/master/LICENSE
 */

package io.javalin.core

import io.javalin.Handler
import io.javalin.core.util.ContextUtil.urlDecode
import java.util.*

data class HandlerEntry(val type: HandlerType, val path: String, val handler: Handler, val rawHandler: Handler) {
    private val pathParser = PathParser(path)
    fun matches(requestUri: String) = pathParser.matches(requestUri)
    fun extractPathParams(requestUri: String) = pathParser.extractPathParams(requestUri)
}

class PathParser(
        path: String,
        private val pathParamNames: List<String> = path.split("/")
                .filter { it.startsWith(":") }
                .map { it.replace(":", "") },
        private val matchRegex: Regex = pathParamNames
                .fold(path) { p, name -> p.replace(":$name", "[^/]+?") } // Replace path param names with wildcards (accepting everything except slash)
                .replace("//", "/") // Replace double slash occurrences
                .replace("/*/", "/.*?/") // Replace star between slashes with wildcard
                .replace("^\\*".toRegex(), ".*?") // Replace star in the beginning of path with wildcard (allow paths like (*/path/)
                .replace("/*", "/.*?") // Replace star in the end of string with wildcard
                .replace("/$".toRegex(), "/?") // Replace trailing slash with optional one
                .run { if (!endsWith("/?")) this + "/?" else this } // Add slash if doesn't have one
                .run { "^" + this + "$" } // Let the matcher know that it is the whole path
                .toRegex(),
        private val pathParamRegex: Regex = matchRegex.pattern.replace("[^/]+?", "([^/]+?)").toRegex(RegexOption.IGNORE_CASE)) {

    fun matches(url: String) = url matches matchRegex

    fun extractPathParams(url: String) = pathParamNames.zip(values(pathParamRegex, url)) { name, value ->
        name to urlDecode(value)
    }.toMap()

    // Match and group values, then drop first element (the input string)
    private fun values(regex: Regex, url: String) = regex.matchEntire(url)?.groupValues?.drop(1) ?: emptyList()

}

class PathMatcher {

    private val handlerEntries = HandlerType.values().associateTo(EnumMap<HandlerType, ArrayList<HandlerEntry>>(HandlerType::class.java)) {
        it to arrayListOf()
    }

    fun add(entry: HandlerEntry) {
        if (entry.type.isHttpMethod() && handlerEntries[entry.type]!!.find { it.type == entry.type && it.path == entry.path } != null) {
            throw IllegalArgumentException("Handler with type='${entry.type}' and path='${entry.path}' already exists.")
        }
        handlerEntries[entry.type]!!.add(entry)
    }

    fun findEntries(handlerType: HandlerType, requestUri: String) =
            handlerEntries[handlerType]!!.filter { he -> match(he, requestUri) }

    private fun match(entry: HandlerEntry, requestPath: String): Boolean = when {
        entry.path == "*" -> true
        entry.path == requestPath -> true
        else -> entry.matches(requestPath)
    }

    private fun slashMismatch(s1: String, s2: String) = (s1.endsWith('/') || s2.endsWith('/')) && (s1.last() != s2.last())

    fun hasEntries() = handlerEntries.flatMap { it.value }.count() > 0

}
