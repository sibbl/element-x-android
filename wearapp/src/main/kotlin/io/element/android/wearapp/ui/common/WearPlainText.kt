/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.common

private val MARKDOWN_IMAGE = Regex("!\\[([^]]*)]\\([^)]*\\)")
private val MARKDOWN_LINK = Regex("\\[([^]]+)]\\([^)]*\\)")
private val MARKDOWN_FENCED_CODE = Regex("(?s)```(?:[A-Za-z0-9_+.-]+)?\\s*(.*?)```")
private val MARKDOWN_INLINE_CODE = Regex("`([^`]+)`")
private val MARKDOWN_STRONG = Regex("(\\*\\*|__)(.+?)\\1")
private val MARKDOWN_EMPHASIS = Regex("(?<![\\p{L}\\p{N}])([*_])([^*_\\n]+?)\\1(?![\\p{L}\\p{N}])")
private val MARKDOWN_STRIKE = Regex("~~(.+?)~~")
private val MARKDOWN_HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
private val MARKDOWN_QUOTE = Regex("(?m)^\\s{0,3}>\\s?")
private val MARKDOWN_LIST = Regex("(?m)^\\s{0,3}[-*+]\\s+")
private val MARKDOWN_ESCAPE = Regex("\\\\([\\\\`*_{}\\[\\]()#+.!>~-])")
private val COLLAPSIBLE_PREVIEW_WHITESPACE = Regex("[\\t\\x0B\\f ]+")
private val EXCESSIVE_PREVIEW_BLANK_LINES = Regex("\\n{3,}")

/** Produces readable Wear preview text from Matrix's plain-body Markdown fallback. */
internal fun String.toWearPlainTextFromMarkdown(): String =
    replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(MARKDOWN_FENCED_CODE, "$1")
        .replace(MARKDOWN_IMAGE, "$1")
        .replace(MARKDOWN_LINK, "$1")
        .replace(MARKDOWN_INLINE_CODE, "$1")
        .replace(MARKDOWN_STRONG, "$2")
        .replace(MARKDOWN_STRIKE, "$1")
        .replace(MARKDOWN_EMPHASIS, "$2")
        .replace(MARKDOWN_HEADING, "")
        .replace(MARKDOWN_QUOTE, "")
        .replace(MARKDOWN_LIST, "• ")
        .replace(MARKDOWN_ESCAPE, "$1")
        .lines()
        .joinToString("\n") { line -> line.replace(COLLAPSIBLE_PREVIEW_WHITESPACE, " ").trimEnd() }
        .replace(EXCESSIVE_PREVIEW_BLANK_LINES, "\n\n")
        .trim()
