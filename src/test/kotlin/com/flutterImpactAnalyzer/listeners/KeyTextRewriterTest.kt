package com.flutterImpactAnalyzer.listeners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyTextRewriterTest {

    private fun rename(content: String, old: String, new: String) =
        KeyTextRewriter.renameKey(content, old, new)

    private fun count(content: String, key: String, depth: Int) =
        KeyTextRewriter.updateWidgetCount(content, key, depth)

    // ---- renameKey ---------------------------------------------------------------

    @Test fun `Key literal keeps its quote style`() =
        assertEquals("Key('xyz')", rename("Key('abc')", "abc", "xyz"))

    @Test fun `ValueKey is not rewritten as Key`() =
        assertEquals("const ValueKey(\"xyz\")", rename("const ValueKey(\"abc\")", "abc", "xyz"))

    @Test fun `ValueKey of String keeps its type argument`() =
        assertEquals("ValueKey<String>('xyz')", rename("ValueKey<String>('abc')", "abc", "xyz"))

    @Test fun `other Key subclasses are never corrupted`() {
        assertEquals("PageStorageKey('abc')", rename("PageStorageKey('abc')", "abc", "xyz"))
        assertEquals("GlobalKey('abc')", rename("GlobalKey('abc')", "abc", "xyz"))
    }

    @Test fun `Keys reference respects word boundary`() =
        assertEquals("Keys.xyz Keys.abcd", rename("Keys.abc Keys.abcd", "abc", "xyz"))

    @Test fun `regex metacharacters in the old key are literal`() =
        assertEquals("Key('q') Key('abc')", rename("Key('a.c') Key('abc')", "a.c", "q"))

    @Test fun `unbalanced regex characters in the old key do not throw`() =
        assertEquals("Key('z')", rename("Key('a(b')", "a(b", "z"))

    @Test fun `dollar sign in new key is escaped for Dart`() =
        assertEquals("Key('a\\\$b')", rename("Key('abc')", "abc", "a\$b"))

    @Test fun `quote in new key is escaped for Dart`() =
        assertEquals("Key('it\\'s')", rename("Key('abc')", "abc", "it's"))

    @Test fun `backslash in new key is escaped for Dart`() =
        assertEquals("Key(\"a\\\\b\")", rename("Key(\"abc\")", "abc", "a\\b"))

    @Test fun `dollar group reference in new Keys name is literal`() =
        assertEquals("Keys.x\$1", rename("Keys.abc", "abc", "x\$1"))

    @Test fun `unrelated keys are untouched`() =
        assertEquals("Key('other')", rename("Key('other')", "abc", "xyz"))

    // ---- updateWidgetCount -------------------------------------------------------

    @Test fun `wrapping toggles const Key finder to findsWidgets`() = assertEquals(
        "expect(find.byKey(const Key('k')), findsWidgets);",
        count("expect(find.byKey(const Key('k')), findsOneWidget);", "k", 1)
    )

    @Test fun `unwrapping toggles Keys finder to findsOneWidget`() = assertEquals(
        "expect(find.byKey(Keys.k), findsOneWidget);",
        count("expect(find.byKey(Keys.k), findsWidgets);", "k", -1)
    )

    @Test fun `findsNothing is left alone`() = assertEquals(
        "expect(find.byKey(Keys.k), findsNothing);",
        count("expect(find.byKey(Keys.k), findsNothing);", "k", -1)
    )

    @Test fun `findsNWidgets is not turned into invalid Dart`() = assertEquals(
        "expect(find.byKey(Keys.k), findsNWidgets(2));",
        count("expect(find.byKey(Keys.k), findsNWidgets(2));", "k", 1)
    )

    @Test fun `similar key name is not matched`() = assertEquals(
        "expect(find.byKey(Keys.kk), findsOneWidget);",
        count("expect(find.byKey(Keys.kk), findsOneWidget);", "k", 1)
    )

    @Test fun `zero depth change is a no-op`() = assertEquals(
        "expect(find.byKey(Keys.k), findsOneWidget);",
        count("expect(find.byKey(Keys.k), findsOneWidget);", "k", 0)
    )

    @Test fun `only the matching expect is changed`() = assertEquals(
        "expect(find.byKey(Keys.k), findsWidgets);\nexpect(find.text('a'), findsOneWidget);",
        count(
            "expect(find.byKey(Keys.k), findsOneWidget);\nexpect(find.text('a'), findsOneWidget);",
            "k", 1
        )
    )

    @Test fun `longer matcher identifier is not partially replaced`() = assertEquals(
        "expect(find.byKey(Keys.k), findsOneWidgetX);",
        count("expect(find.byKey(Keys.k), findsOneWidgetX);", "k", 1)
    )

    // ---- isTestFile --------------------------------------------------------------

    @Test fun `test directory and _test suffix are tests`() {
        val base = "/work/app"
        assertTrue(KeyTextRewriter.isTestFile(base, "/work/app/test/a/b_test.dart"))
        assertTrue(KeyTextRewriter.isTestFile(base, "/work/app/test/helpers/finders.dart"))
        assertTrue(KeyTextRewriter.isTestFile(base, "/work/app/integration_test/flow.dart"))
        assertTrue(KeyTextRewriter.isTestFile(base, "/work/app/lib/odd_test.dart"))
    }

    @Test fun `production paths containing the word test are not tests`() {
        assertFalse(KeyTextRewriter.isTestFile("/work/app", "/work/app/lib/features/contest/page.dart"))
        assertFalse(KeyTextRewriter.isTestFile("/work/app", "/work/app/lib/latest.dart"))
    }

    @Test fun `project located in a folder containing test is judged by relative path`() {
        val base = "/home/me/testing/latest_app"
        assertFalse(KeyTextRewriter.isTestFile(base, "$base/lib/main.dart"))
        assertTrue(KeyTextRewriter.isTestFile(base, "$base/test/main_test.dart"))
    }
}
