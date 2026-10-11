package dev.amang.claudetermux.bridge

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Turns on Termux's `allow-external-apps` setting, which Termux requires before it accepts
 * RUN_COMMAND intents. The setting lives in Termux's private home folder, so the user grants
 * access to that folder once through the system folder picker (Termux exposes it as a documents
 * provider) and the app edits the file itself.
 */
object TermuxAccess {
    private const val AUTHORITY = "com.termux.documents"
    private const val CONFIG_DIR = "${TermuxLauncher.HOME}/.termux"
    private const val PROPERTIES = "$CONFIG_DIR/termux.properties"

    /** Where the folder picker should open. */
    val homeUri: Uri get() = DocumentsContract.buildDocumentUri(AUTHORITY, TermuxLauncher.HOME)

    /** Returns null on success, otherwise what went wrong. [tree] is what the folder picker returned. */
    fun allowExternalApps(context: Context, tree: Uri): String? {
        if (tree.authority != AUTHORITY || DocumentsContract.getTreeDocumentId(tree) != TermuxLauncher.HOME) {
            return "That was not the Termux home folder. Try again and tap \"Use this folder\" without opening any other folder."
        }
        val resolver = context.contentResolver
        fun document(path: String) = DocumentsContract.buildDocumentUriUsingTree(tree, path)
        fun exists(path: String) = resolver.query(document(path), null, null, null, null)?.use { it.count > 0 } ?: false
        return try {
            if (!exists(CONFIG_DIR)) {
                DocumentsContract.createDocument(resolver, document(TermuxLauncher.HOME), DocumentsContract.Document.MIME_TYPE_DIR, ".termux")
            }
            val current = if (exists(PROPERTIES)) {
                resolver.openInputStream(document(PROPERTIES))?.use { it.readBytes().decodeToString() }.orEmpty()
            } else {
                DocumentsContract.createDocument(resolver, document(CONFIG_DIR), "application/octet-stream", "termux.properties")
                ""
            }
            val updated = withExternalAppsAllowed(current)
            if (updated != current) {
                resolver.openOutputStream(document(PROPERTIES), "wt")?.use { it.write(updated.toByteArray()) }
                    ?: return "Could not write Termux's settings file."
            }
            null
        } catch (e: Exception) {
            "Could not update Termux's settings (${e.message ?: e.javaClass.simpleName}). Open Termux once so it finishes its own first-time install, then try again."
        }
    }

    /** [properties] with `allow-external-apps = true`, replacing any existing or commented-out value. */
    internal fun withExternalAppsAllowed(properties: String): String {
        val line = "allow-external-apps = true"
        val existing = Regex("""^[ \t]*#?[ \t]*allow-external-apps[ \t]*=.*$""", RegexOption.MULTILINE)
        if (existing.containsMatchIn(properties)) return existing.replace(properties) { line }
        val separator = if (properties.isEmpty() || properties.endsWith("\n")) "" else "\n"
        return "$properties$separator$line\n"
    }
}
