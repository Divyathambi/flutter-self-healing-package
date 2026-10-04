package com.flutterImpactAnalyzer.listeners

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.codeInsight.completion.CompletionUtilCore

import com.jetbrains.lang.dart.DartFileType
import com.jetbrains.lang.dart.psi.DartCallExpression
import com.jetbrains.lang.dart.psi.DartNewExpression
import com.jetbrains.lang.dart.psi.DartReferenceExpression
import com.jetbrains.lang.dart.psi.DartStringLiteralExpression
import com.jetbrains.lang.dart.psi.DartVarDeclarationList
import com.jetbrains.lang.dart.util.DartPsiImplUtil

/**
 * Dart-aware analysis backed by the Dart plugin PSI instead of regex/substring
 * matching. Because keys are read from real PSI nodes (reference expressions,
 * call expressions, string literals) matches inside comments or unrelated
 * strings no longer produce false positives.
 */
object DartKeyAnalyzer {

    private val keyCtorNames = setOf("Key", "ValueKey")

    // Names carried by the completion popup's dummy identifier are ignored.
    private fun String.isDummy() = contains(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED)

    private fun parse(project: Project, text: String): PsiFile =
        PsiFileFactory.getInstance(project)
            .createFileFromText("__impact_probe__.dart", DartFileType.INSTANCE, text)

    /** Callee name of a widget/constructor call, unqualified (e.g. "Padding", "Key"). */
    private fun calleeName(el: PsiElement): String? {
        val raw = when (el) {
            is DartCallExpression -> el.expression?.text
            is DartNewExpression ->
                PsiTreeUtil.findChildOfType(el, DartReferenceExpression::class.java)?.text
            else -> null
        } ?: return null
        return raw.substringAfterLast('.')
    }

    /** Unquoted value of the first positional string argument of a Key(...) call. */
    private fun literalKeyOf(call: DartCallExpression): String? {
        val callee = call.expression?.text ?: return null
        if (callee.substringAfterLast('.') !in keyCtorNames) return null
        val arg = call.arguments?.argumentList?.expressionList?.firstOrNull()
        if (arg !is DartStringLiteralExpression) return null
        return DartPsiImplUtil.getUnquotedDartStringAndItsRange(arg.text).first
    }

    /**
     * Key *usages* found in a PSI subtree: both `Keys.foo` references and the
     * literal value of `Key("foo")` / `ValueKey("foo")` constructor calls.
     */
    fun usageKeysIn(root: PsiElement): Set<String> {
        val keys = mutableSetOf<String>()

        PsiTreeUtil.findChildrenOfType(root, DartReferenceExpression::class.java)
            .forEach { ref ->
                val t = ref.text
                if (t.startsWith("Keys.")) {
                    val name = t.substring(5)
                    if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }) {
                        keys.add(name)
                    }
                }
            }

        PsiTreeUtil.findChildrenOfType(root, DartCallExpression::class.java)
            .forEach { call -> literalKeyOf(call)?.let { keys.add(it) } }

        return keys.filterNot { it.isDummy() }.toSet()
    }

    /**
     * Names of key *definitions*, e.g. `static const foo = Key(...)` — collected
     * as the variable names whose initializer is a Key/ValueKey constructor call.
     */
    fun definitionKeysIn(root: PsiElement): Set<String> {
        val keys = mutableSetOf<String>()

        PsiTreeUtil.findChildrenOfType(root, DartCallExpression::class.java)
            .forEach { call ->
                val callee = call.expression?.text?.substringAfterLast('.') ?: return@forEach
                if (callee !in keyCtorNames) return@forEach
                val decl = PsiTreeUtil.getParentOfType(call, DartVarDeclarationList::class.java)
                    ?: return@forEach
                decl.varAccessDeclaration.componentName.name?.let { keys.add(it) }
            }

        return keys.filterNot { it.isDummy() }.toSet()
    }

    fun usageKeys(project: Project, text: String): Set<String> =
        usageKeysIn(parse(project, text))

    fun definitionKeys(project: Project, text: String): Set<String> =
        definitionKeysIn(parse(project, text))

    /**
     * Ordered list of definition variable names (e.g. `loginButton` from
     * `static const loginButton = Key('login')`) in file/declaration order.
     */
    fun definitionNamesIn(root: PsiElement): List<String> {
        val names = mutableListOf<String>()

        PsiTreeUtil.findChildrenOfType(root, DartCallExpression::class.java)
            .forEach { call ->
                val callee = call.expression?.text?.substringAfterLast('.') ?: return@forEach
                if (callee !in keyCtorNames) return@forEach
                val decl = PsiTreeUtil.getParentOfType(call, DartVarDeclarationList::class.java)
                    ?: return@forEach
                val name = decl.varAccessDeclaration.componentName.name ?: return@forEach
                if (!name.isDummy()) names.add(name)
            }

        return names
    }

    fun definitionNames(project: Project, text: String): List<String> =
        definitionNamesIn(parse(project, text))

    /** Minimum name similarity for two names to be considered the same key renamed. */
    private const val RENAME_SIMILARITY_THRESHOLD = 0.5

    /**
     * Detect definition (variable-name) renames between two states of keys.dart.
     *
     * A rename is a variable name that disappeared paired with the name that
     * appeared in its place. Candidates are paired by *name similarity* rather than
     * declaration order: a rename is an incremental edit of an identifier, so the
     * correct counterpart is always the most similar name. This prevents unrelated
     * keys from being mis-paired (e.g. `loginTapToCloseKey` matched against
     * `headerActionBackddd`) when several definitions change at once or the
     * declaration order shifts.
     *
     * Pairing is greedy on the best global match and each name is used once, so
     * multiple simultaneous renames are each detected. Names with no sufficiently
     * similar counterpart are left unpaired and reported as nothing, which means a
     * pure addition or deletion is correctly not treated as a rename.
     */
    fun detectDefinitionRenames(
        oldNames: List<String>,
        newNames: List<String>
    ): List<Pair<String, String>> {

        val newSet = newNames.toSet()
        val oldSet = oldNames.toSet()
        val removed = oldNames.filter { it !in newSet }.distinct().toMutableList()
        val added = newNames.filter { it !in oldSet }.distinct().toMutableList()

        if (removed.isEmpty() || added.isEmpty()) return emptyList()

        // Exactly one name gone and one appeared: unambiguous, so accept it even if
        // the new name shares nothing with the old one (a full rename, not an edit).
        if (removed.size == 1 && added.size == 1) {
            return listOf(removed[0] to added[0])
        }

        val renames = mutableListOf<Pair<String, String>>()

        // Repeatedly commit the single best-scoring pair, then remove both names
        // from the pool, until no pair clears the similarity threshold.
        while (removed.isNotEmpty() && added.isNotEmpty()) {
            var bestOld: String? = null
            var bestNew: String? = null
            var bestScore = RENAME_SIMILARITY_THRESHOLD

            for (o in removed) {
                for (n in added) {
                    val score = similarity(o, n)
                    if (score > bestScore) {
                        bestScore = score
                        bestOld = o
                        bestNew = n
                    }
                }
            }

            val o = bestOld ?: break
            val n = bestNew ?: break
            renames.add(o to n)
            removed.remove(o)
            added.remove(n)
        }

        return renames
    }

    /** Normalized (0..1) similarity between two identifiers. */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0
        return (longest - levenshtein(a, b)).toDouble() / longest
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }

        return previous[b.length]
    }

    /**
     * A rename is exactly one key removed and one added between two states.
     */
    fun detectRename(oldKeys: Set<String>, newKeys: Set<String>): Pair<String, String>? {
        val removed = (oldKeys - newKeys).toList()
        val added = (newKeys - oldKeys).toList()
        if (removed.size == 1 && added.size == 1) {
            return removed[0] to added[0]
        }
        return null
    }

    /**
     * Real widget nesting depth of the widget that owns [key], computed from the
     * PSI tree: the number of ancestor widget-constructor calls (callee starting
     * with an uppercase letter) above the element that references the key.
     */
    fun widgetDepth(project: Project, text: String, key: String): Int {
        val file = parse(project, text)
        val keyElement = findKeyElement(file, key) ?: return 0

        var depth = 0
        var parent: PsiElement? = keyElement.parent
        while (parent != null) {
            if (parent is DartCallExpression || parent is DartNewExpression) {
                val callee = calleeName(parent)
                if (callee != null &&
                    callee !in keyCtorNames &&
                    callee.firstOrNull()?.isUpperCase() == true
                ) {
                    depth++
                }
            }
            parent = parent.parent
        }
        return depth
    }

    /** The PSI element that references [key] (either `Keys.key` or `Key("key")`). */
    private fun findKeyElement(root: PsiElement, key: String): PsiElement? {
        PsiTreeUtil.findChildrenOfType(root, DartReferenceExpression::class.java)
            .firstOrNull { it.text == "Keys.$key" }
            ?.let { return it }

        return PsiTreeUtil.findChildrenOfType(root, DartCallExpression::class.java)
            .firstOrNull { literalKeyOf(it) == key }
    }

    /**
     * Project files (excluding [currentFilePath]) that use [key], using cached
     * Dart PSI so comments and unrelated strings are ignored.
     */
    fun findImpactedFiles(
        project: Project,
        key: String,
        currentFilePath: String
    ): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        val psiManager = PsiManager.getInstance(project)

        val files = FilenameIndex.getAllFilesByExt(
            project,
            "dart",
            GlobalSearchScope.projectScope(project)
        )

        for (file in files) {
            if (file.path == currentFilePath) continue
            val psiFile = psiManager.findFile(file) ?: continue
            if (usageKeysIn(psiFile).contains(key)) {
                result.add(file)
            }
        }

        return result
    }
}
