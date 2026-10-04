package com.flutterImpactAnalyzer.listeners

/**
 * Pure text rewriting used by the legacy key-change dialogs. No IDE classes, so it can
 * be unit-tested without a running platform.
 *
 * Differences from the previous inline regexes in KeyChangeListener:
 *  - key values are regex-escaped (a key such as `btn.login` or `a+b` no longer
 *    over-matches or throws PatternSyntaxException);
 *  - replacement text is escaped (a `$` or `\` in a key no longer throws);
 *  - `Key(` / `ValueKey(` must start a token, so `PageStorageKey('x')` is never
 *    corrupted into `PageStorageKeyKey("x")`;
 *  - the original constructor, `<String>` type argument and quote style are kept
 *    (`ValueKey('x')` used to be silently rewritten as `Key("x")`);
 *  - `find.byKey(const Key('x'))` is recognised (the `const` form never matched);
 *  - only `findsOneWidget` <-> `findsWidgets` is toggled. `findsNothing` and
 *    `findsNWidgets(n)` used to be rewritten as well, producing wrong or invalid Dart
 *    such as `findsWidgets(2)`.
 */
internal object KeyTextRewriter {

    /** Renames `Keys.old` references and `Key/ValueKey('old')` literals. */
    fun renameKey(content: String, oldKey: String, newKey: String): String {
        val old = Regex.escape(oldKey)

        var result = content.replace(
            Regex("""\bKeys\.$old\b"""),
            Regex.escapeReplacement("Keys.$newKey")
        )

        val literal = Regex(
            """(?<![\w$])((?:Value)?Key(?:\s*<\s*String\s*>)?\s*\(\s*)(["'])$old\2(\s*\))"""
        )
        result = result.replace(literal) { m ->
            val quote = m.groupValues[2]
            m.groupValues[1] + quote + dartEscape(newKey, quote) + quote + m.groupValues[3]
        }
        return result
    }

    /**
     * Toggles `expect(find.byKey(<key>), findsOneWidget|findsWidgets)` according to the
     * direction of the widget-depth change. Any other matcher is left untouched.
     */
    fun updateWidgetCount(content: String, key: String, depthChange: Int): String {
        val replacement = when {
            depthChange > 0 -> "findsWidgets"
            depthChange < 0 -> "findsOneWidget"
            else -> return content
        }
        val k = Regex.escape(key)
        val finder =
            """find\.byKey\s*\(\s*(?:Keys\.$k\b|(?:const\s+)?(?:Value)?Key\s*\(\s*["']$k["']\s*\))\s*\)"""
        val expectCall = Regex(
            """(expect\s*\(\s*$finder\s*,\s*)(?:findsOneWidget|findsWidgets)(?!\w)"""
        )
        return content.replace(expectCall) { m -> m.groupValues[1] + replacement }
    }

    /**
     * True for Flutter test sources: `*_test.dart`, or anything under a `test`,
     * `integration_test` or `test_driver` directory of the project. Previously any path
     * merely containing the text "test" qualified, so a project checked out under e.g.
     * `/home/me/latest/app/` treated every production file as a test.
     */
    fun isTestFile(projectBase: String?, path: String): Boolean {
        val normalized = path.replace('\\', '/')
        val base = projectBase?.replace('\\', '/')?.trimEnd('/')
        val relative = if (base != null && normalized.startsWith("$base/")) {
            normalized.substring(base.length + 1)
        } else {
            normalized
        }
        val segments = relative.split('/')
        if (segments.last().endsWith("_test.dart")) return true
        return segments.dropLast(1).any {
            it == "test" || it == "integration_test" || it == "test_driver"
        }
    }

    private fun dartEscape(value: String, quote: String): String =
        value
            .replace("\\", "\\\\")
            .replace("$", "\\$")
            .replace(quote, "\\" + quote)
            .replace("\n", "\\n")
            .replace("\r", "\\r")
}
